/*
 * Copyright (C) 2024 pedroSG94.
 * Modifications copyright (C) 2026 BlueScorpioVR (ScreenStreamerXR).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bluescorpiovr.screenstreamerxr.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.base.recording.RecordController
import com.pedro.library.generic.GenericStream
import com.bluescorpiovr.screenstreamerxr.R
import com.bluescorpiovr.screenstreamerxr.utils.PathUtils
import com.bluescorpiovr.screenstreamerxr.utils.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Interface to communicate service events to Activity
 */
interface ScreenServiceCallback : ConnectChecker {
  fun onNotificationStatusChange(enabled: Boolean)
  fun onLogMessage(message: String)
  fun onEventsListeningChange(listening: Boolean)
}

/**
 * Basic Screen service streaming implementation
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class ScreenService: Service(), ConnectChecker {

  companion object {
    private const val TAG = "DisplayService"
    private const val CHANNEL_ID = "DisplayStreamChannel"
    private const val LEGACY_CHAT_CHANNEL_ID = "ChatChannel"
    //a new id is required because channel alerting settings are immutable once created
    private const val CHAT_CHANNEL_ID = "ChatChannelAlerts"
    //chat messages rotate over several ids so each one posts as a new system event
    private const val CHAT_NOTIFY_SLOTS = 5
    const val NOTIFY_ID = 123456
    const val CHAT_NOTIFY_ID = 123457
    const val VIEWER_NOTIFY_ID = 123462
    var INSTANCE: ScreenService? = null
  }

  private var notificationManager: NotificationManager? = null
  private lateinit var genericStream: GenericStream
  private var mediaProjection: MediaProjection? = null
  private val mediaProjectionManager: MediaProjectionManager by lazy {
    applicationContext.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
  }
  private var callback: ScreenServiceCallback? = null
  private var width = 1920
  private var height = 1080
  private var vBitrate = 6_000_000
  private var fps = 30
  private var rotation = 0 //0 for landscape
  private val sampleRate = 48000
  private val isStereo = true
  private val aBitrate = 128 * 1000
  private var prepared = false
  private var recordPath = ""
  private var selectedAudioSource: Int = R.id.audio_source_mix

  private var tts: TextToSpeech? = null
  private var ttsEnabled = false
  private var systemNotificationsEnabled = true
  private var chatNotificationSlot = 0
  private var chatJob: Job? = null
  private val scope = CoroutineScope(Dispatchers.IO)
  private var omniStreamClient: OmniStreamClient? = null

  override fun onCreate() {
    super.onCreate()
    Log.i(TAG, "RTP Display service create")
    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(CHANNEL_ID, CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH)
      notificationManager?.createNotificationChannel(channel)
      notificationManager?.deleteNotificationChannel(LEGACY_CHAT_CHANNEL_ID)
      val chatChannel = NotificationChannel(CHAT_CHANNEL_ID, "Stream Chat", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Notifications for incoming chat messages"
        setSound(
          RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
          AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        )
        enableVibration(true)
        enableLights(true)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      }
      notificationManager?.createNotificationChannel(chatChannel)
    }
    val streamPrefs = getSharedPreferences("xr_stream_prefs", MODE_PRIVATE)
    ttsEnabled = streamPrefs.getBoolean("tts_enabled", false)
    systemNotificationsEnabled = streamPrefs.getBoolean("system_notifications_enabled", true)
    val savedResolutionIndex = streamPrefs.getInt("resolution_pos", 0)
    val savedTarget = streamPrefs.getInt("target", 0)
    val twitchCompatibleIndex = if (savedTarget == ScreenActivity.TARGET_TWITCH) {
      savedResolutionIndex.coerceAtMost(1)
    } else {
      savedResolutionIndex
    }
    applyResolutionIndex(twitchCompatibleIndex)
    genericStream = GenericStream(baseContext, this, NoVideoSource(), MicrophoneSource()).apply {
      getGlInterface().setForceRender(true, fps)
      getGlInterface().autoHandleOrientation = false
      setVideoCodec(VideoCodec.H264)
      setAudioCodec(AudioCodec.AAC)
    }
    prepared = try {
      genericStream.prepareVideo(width, height, vBitrate, fps = fps, rotation = rotation, iFrameInterval = 2) &&
          genericStream.prepareAudio(sampleRate, isStereo, aBitrate,
            echoCanceler = true,
            noiseSuppressor = true
          )
    } catch (_: IllegalArgumentException) {
      false
    }
    if (prepared) {
      INSTANCE = this
      initTTS()
    } else {
      toast("Invalid audio or video parameters, prepare failed")
    }
  }

  private fun initTTS() {
    tts = TextToSpeech(this) { status ->
      if (status == TextToSpeech.SUCCESS) {
        tts?.language = Locale.US
      }
    }
  }

  fun setTtsEnabled(enabled: Boolean) {
    ttsEnabled = enabled
  }

  fun setSystemNotificationsEnabled(enabled: Boolean) {
    systemNotificationsEnabled = enabled
    if (enabled && !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
      log("Notifications are blocked for this app in system settings")
      callback?.onNotificationStatusChange(false)
    }
  }

  fun setStreamResolution(index: Int) {
    val previousWidth = width
    val previousHeight = height
    val previousFps = fps
    val previousBitrate = vBitrate
    applyResolutionIndex(index)
    if (previousWidth == width && previousHeight == height && previousFps == fps && previousBitrate == vBitrate) return
    if (genericStream.isStreaming || genericStream.isRecording || genericStream.isOnPreview) {
      applyResolutionIndexValues(previousWidth, previousHeight, previousFps, previousBitrate)
      return
    }
    genericStream.getGlInterface().setForceRender(true, fps)
    val videoPrepared = try {
      genericStream.prepareVideo(width, height, vBitrate, fps = fps, rotation = rotation, iFrameInterval = 2)
    } catch (_: IllegalArgumentException) {
      false
    }
    if (!videoPrepared) {
      applyResolutionIndexValues(previousWidth, previousHeight, previousFps, previousBitrate)
      genericStream.getGlInterface().setForceRender(true, fps)
      toast("Invalid video parameters, prepare failed")
    }
  }

  private fun applyResolutionIndex(index: Int) {
    when (index) {
      1 -> applyResolutionIndexValues(1920, 1080, 60, 10_000_000)
      2 -> applyResolutionIndexValues(2560, 1440, 30, 15_000_000)
      3 -> applyResolutionIndexValues(2560, 1440, 60, 20_000_000)
      4 -> applyResolutionIndexValues(3840, 2160, 30, 35_000_000)
      5 -> applyResolutionIndexValues(3840, 2160, 60, 50_000_000)
      else -> applyResolutionIndexValues(1920, 1080, 30, 6_000_000)
    }
  }

  private fun applyResolutionIndexValues(streamWidth: Int, streamHeight: Int, streamFps: Int, streamBitrate: Int) {
    width = streamWidth
    height = streamHeight
    fps = streamFps
    vBitrate = streamBitrate
  }

  fun startChatListener(channel: String) {
    omniStreamClient?.stop()
    chatJob?.cancel()
    log("Twitch: Connecting to channel #$channel")
    keepAliveTrick()
    chatJob = scope.launch {
      try {
        Socket("irc.chat.twitch.tv", 6667).use { socket ->
          val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
          val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

          writer.write("PASS SCHMOOPIIE\r\n") // Anonymous login
          writer.write("NICK justinfan123\r\n")
          writer.write("CAP REQ :twitch.tv/tags twitch.tv/commands\r\n")
          writer.write("JOIN #$channel\r\n")
          writer.flush()

          log("Twitch: Connected, listening for messages")
          callback?.onNotificationStatusChange(true)

          while (true) {
            val line = reader.readLine() ?: break
            if (line.startsWith("PING")) {
              writer.write("PONG :tmi.twitch.tv\r\n")
              writer.flush()
            } else {
              parseTwitchPrivmsg(line)?.let { (user, message) ->
                announceChatMessage(user, message)
              }
            }
          }
        }
      } catch (e: Exception) {
        log("Twitch Error: ${e.message}")
        callback?.onNotificationStatusChange(false)
        Log.e(TAG, "Chat error", e)
      } finally {
        if (chatJob === coroutineContext[Job]) {
          chatJob = null
          callback?.onEventsListeningChange(false)
        }
      }
    }
    callback?.onEventsListeningChange(true)
  }

  fun startYouTubeChatListener(videoId: String, apiKey: String) {
    omniStreamClient?.stop()
    chatJob?.cancel()
    log("YouTube: Fetching liveChatId for video $videoId")
    keepAliveTrick()
    chatJob = scope.launch {
      try {
        var liveChatId: String? = null
        
        // 1. Get LiveChatId from VideoId
        val videoUrl = URL("https://www.googleapis.com/youtube/v3/videos?part=liveStreamingDetails&id=$videoId&key=$apiKey")
        val videoConnection = videoUrl.openConnection() as HttpURLConnection
        val videoResponse = try {
            videoConnection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            videoConnection.errorStream?.bufferedReader()?.use { it.readText() } ?: throw e
        }
        val videoJson = JSONObject(videoResponse)
        
        if (videoJson.has("error")) {
            val errorMsg = videoJson.getJSONObject("error").getString("message")
            log("YouTube API Error: $errorMsg")
            callback?.onNotificationStatusChange(false)
            return@launch
        }

        val items = videoJson.getJSONArray("items")
        if (items.length() > 0) {
            liveChatId = items.getJSONObject(0).getJSONObject("liveStreamingDetails").optString("activeLiveChatId")
        }

        if (liveChatId.isNullOrBlank()) {
            log("YouTube Error: No active live chat found for this video. Is the stream live?")
            callback?.onNotificationStatusChange(false)
            return@launch
        }

        log("YouTube: Found Chat ID $liveChatId. Starting poll.")
        callback?.onNotificationStatusChange(true)
        var nextPageToken: String? = null
        val seenMessageIds = mutableSetOf<String>()

        // 2. Poll messages
        while (isActive) {
            val chatUrlString = "https://www.googleapis.com/youtube/v3/liveChat/messages?liveChatId=$liveChatId&part=snippet,authorDetails&key=$apiKey" +
                (if (nextPageToken != null) "&pageToken=$nextPageToken" else "")
            
            val chatUrl = URL(chatUrlString)
            val chatConnection = chatUrl.openConnection() as HttpURLConnection
            val chatResponse = try {
                chatConnection.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                chatConnection.errorStream?.bufferedReader()?.use { it.readText() } ?: throw e
            }
            val chatJson = JSONObject(chatResponse)
            
            if (chatJson.has("error")) {
                val errorMsg = chatJson.getJSONObject("error").getString("message")
                log("YouTube Poll Error: $errorMsg")
                callback?.onNotificationStatusChange(false)
                break
            }

            nextPageToken = chatJson.optString("nextPageToken")
            val pollingIntervalMillis = chatJson.optLong("pollingIntervalMillis", 5000L)
            
            val messages = chatJson.getJSONArray("items")
            for (i in 0 until messages.length()) {
                val msg = messages.getJSONObject(i)
                val id = msg.getString("id")
                if (id !in seenMessageIds) {
                    seenMessageIds.add(id)
                    val author = msg.getJSONObject("authorDetails").getString("displayName")
                    val text = msg.getJSONObject("snippet").getString("displayMessage")
                    announceChatMessage(author, text)
                }
            }
            
            if (seenMessageIds.size > 200) seenMessageIds.clear()
            delay(pollingIntervalMillis)
        }
      } catch (e: Exception) {
        log("YouTube Error: ${e.message}")
        callback?.onNotificationStatusChange(false)
        Log.e(TAG, "YouTube Chat error", e)
      } finally {
        if (chatJob === coroutineContext[Job]) {
          chatJob = null
          callback?.onEventsListeningChange(false)
        }
      }
    }
    callback?.onEventsListeningChange(true)
  }

  fun startOmniStreamListener(host: String) {
    chatJob?.cancel()
    chatJob = null
    keepAliveTrick()
    omniStream().start(host)
    callback?.onEventsListeningChange(true)
  }

  private fun omniStream(): OmniStreamClient {
    val existingClient = omniStreamClient
    if (existingClient != null) return existingClient
    val createdClient = OmniStreamClient(scope, object : OmniStreamClient.Callback {
      override fun onLog(message: String) = log(message)
      override fun onChatMessage(author: String, message: String) = announceChatMessage(author, message)
      override fun onAlert(title: String, summary: String) = announceAlert(title, summary)
      override fun onViewerCountsChanged(total: Int, perPlatform: List<PlatformViewerCount>) {
        announceViewerCounts(total, perPlatform)
      }
      override fun onStatusChange(connected: Boolean) {
        callback?.onNotificationStatusChange(connected)
      }
    })
    omniStreamClient = createdClient
    return createdClient
  }

  fun stopChatListener() {
    log("Chat: Stopped")
    chatJob?.cancel()
    chatJob = null
    omniStreamClient?.stop()
    notificationManager?.cancel(VIEWER_NOTIFY_ID)
    callback?.onEventsListeningChange(false)
  }

  fun isListeningToEvents(): Boolean {
    return chatJob?.isActive == true || omniStreamClient?.isRunning() == true
  }

  private fun log(message: String) {
    val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    callback?.onLogMessage("[$timestamp] $message")
  }

  private fun parseTwitchPrivmsg(line: String): Pair<String, String>? {
    var rest = line
    var displayName = ""
    if (rest.startsWith("@")) {
      val tagsEnd = rest.indexOf(' ')
      if (tagsEnd == -1) return null
      displayName = twitchTagValue(rest.substring(1, tagsEnd), "display-name")
      rest = rest.substring(tagsEnd + 1)
    }
    val privmsgIndex = rest.indexOf("PRIVMSG ")
    if (privmsgIndex == -1) return null
    val prefix = rest.substring(0, privmsgIndex).trim()
    val nick = if (prefix.startsWith(":")) {
      prefix.substring(1).substringBefore("!").substringBefore(" ")
    } else {
      ""
    }
    val user = displayName.ifBlank { nick }
    if (user.isBlank()) return null
    return user to rest.substring(privmsgIndex).substringAfter(" :")
  }

  private fun twitchTagValue(tags: String, key: String): String {
    val prefix = "$key="
    tags.split(';').forEach { tag ->
      if (!tag.startsWith(prefix)) return@forEach
      return tag.substring(prefix.length)
        .replace("\\s", " ")
        .replace("\\:", ";")
        .replace("\\\\", "\\")
        .replace("\\r", "")
        .replace("\\n", "")
    }
    return ""
  }

  private fun speakableText(text: String): String {
    val filtered = buildString {
      for (character in text) {
        when {
          character.isLetterOrDigit() || character.isWhitespace() -> append(character)
          character == '_' -> append(' ')
          character in ".,!?'-" -> append(character)
        }
      }
    }
    return filtered.replace(Regex("\\s+"), " ").trim()
  }

  private fun announceChatMessage(user: String, message: String) {
    if (ttsEnabled) {
      val spokenUser = speakableText(user).ifBlank { "someone" }
      val spokenMessage = speakableText(message)
      if (spokenMessage.isNotBlank()) {
        tts?.speak("$spokenUser says: $spokenMessage", TextToSpeech.QUEUE_ADD, null, null)
      }
    }
    if (systemNotificationsEnabled) showChatNotification(user, message)
    log("Message from $user: $message")
  }

  private fun announceAlert(title: String, summary: String) {
    val spoken = speakableText(if (summary.isBlank()) title else "$title. $summary")
    if (ttsEnabled && spoken.isNotBlank()) {
      tts?.speak(spoken, TextToSpeech.QUEUE_ADD, null, null)
    }
    if (systemNotificationsEnabled) showChatNotification(title, summary.ifBlank { title })
    log("Alert: $title — $summary")
  }

  private fun announceViewerCounts(total: Int, perPlatform: List<PlatformViewerCount>) {
    val platformSummary = perPlatform.joinToString(" · ") { "${it.label}: ${it.viewerCount}" }
    val body = if (platformSummary.isBlank()) "$total viewers" else "$total viewers ($platformSummary)"
    if (ttsEnabled) {
      val spokenPlatforms = perPlatform.joinToString(", ") { "${it.label} ${it.viewerCount}" }
      val spoken = if (spokenPlatforms.isBlank()) "$total viewers" else "$total viewers. $spokenPlatforms"
      tts?.speak(spoken, TextToSpeech.QUEUE_ADD, null, "viewer_count")
    }
    if (systemNotificationsEnabled) showViewerNotification(total, body)
    log("Viewers: $body")
  }

  private fun showViewerNotification(total: Int, body: String) {
    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
      log("Notification skipped, notifications are blocked in system settings")
      callback?.onNotificationStatusChange(false)
      return
    }
    val notification = NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
      .setSmallIcon(R.drawable.notification_icon)
      .setContentTitle("$total viewers")
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setDefaults(NotificationCompat.DEFAULT_ALL)
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setWhen(System.currentTimeMillis())
      .setAutoCancel(false)
      .build()
    notificationManager?.notify(VIEWER_NOTIFY_ID, notification)
  }

  private fun showChatNotification(user: String, message: String) {
    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
      log("Notification skipped, notifications are blocked in system settings")
      callback?.onNotificationStatusChange(false)
      return
    }
    val notification = NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
      .setSmallIcon(R.drawable.notification_icon)
      .setContentTitle("Chat: $user")
      .setContentText(message)
      .setStyle(NotificationCompat.BigTextStyle().bigText(message))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setDefaults(NotificationCompat.DEFAULT_ALL)
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setWhen(System.currentTimeMillis())
      .setAutoCancel(true)
      .build()
    notificationManager?.notify(CHAT_NOTIFY_ID + chatNotificationSlot, notification)
    chatNotificationSlot = (chatNotificationSlot + 1) % CHAT_NOTIFY_SLOTS
  }

  private fun keepAliveTrick() {
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.notification_icon)
      .setContentTitle("Screen Stream XR")
      .setContentText(if (genericStream.isStreaming) "Streaming" else "Listening for events")
      .setSilent(true)
      .setOngoing(false)
      .build()
    if (Build.VERSION.SDK_INT >= 34) {
      val serviceTypes = if (mediaProjection != null) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
      } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
      }
      startForeground(1, notification, serviceTypes)
    } else {
      startForeground(1, notification)
    }
  }

  override fun onBind(p0: Intent?): IBinder? {
    return null
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.i(TAG, "RTP Display service started")
    return START_STICKY
  }

  fun sendIntent(): Intent {
    return mediaProjectionManager.createScreenCaptureIntent()
  }

  fun isStreaming(): Boolean {
    return genericStream.isStreaming
  }

  fun isRecording(): Boolean {
    return genericStream.isRecording
  }

  fun stopStream() {
    if (genericStream.isStreaming) {
      genericStream.stopStream()
      notificationManager?.cancel(NOTIFY_ID)
    }
  }

  fun setCallback(cb: ScreenServiceCallback?) {
    callback = cb
  }

  override fun onDestroy() {
    super.onDestroy()
    Log.i(TAG, "RTP Display service destroy")
    stopStream()
    stopChatListener()
    tts?.stop()
    tts?.shutdown()
    INSTANCE = null
    //release stream and media projection properly
    genericStream.release()
    mediaProjection?.stop()
    mediaProjection = null
  }

  fun prepareStream(resultCode: Int, data: Intent): Boolean {
    keepAliveTrick()
    stopStream()
    mediaProjection?.stop()
    val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data) ?: throw IllegalStateException("get MediaProjection failed")
    this.mediaProjection = mediaProjection
    val screenSource = ScreenSource(applicationContext, mediaProjection)
    return try {
      genericStream.changeVideoSource(screenSource)
      toggleAudioSource(selectedAudioSource)
      true
    } catch (_: IllegalArgumentException) {
      false
    }
  }

  fun getCurrentAudioSource(): AudioSource = genericStream.audioSource

  fun toggleAudioSource(itemId: Int) {
    when (itemId) {
      R.id.audio_source_microphone -> {
        selectedAudioSource = R.id.audio_source_microphone
        if (genericStream.audioSource is MicrophoneSource) return
        genericStream.changeAudioSource(MicrophoneSource())
      }
      R.id.audio_source_internal -> {
        selectedAudioSource = R.id.audio_source_internal
        if (genericStream.audioSource is InternalAudioSource) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          mediaProjection?.let { genericStream.changeAudioSource(InternalAudioSource(it)) }
        } else {
          throw IllegalArgumentException("You need min API 29+")
        }
      }
      R.id.audio_source_mix -> {
        selectedAudioSource = R.id.audio_source_mix
        if (genericStream.audioSource is MixAudioSource) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          mediaProjection?.let { genericStream.changeAudioSource(MixAudioSource(it)) }
        } else {
          throw IllegalArgumentException("You need min API 29+")
        }
      }
    }
  }

  fun toggleRecord(state: (RecordController.Status) -> Unit) {
    if (!genericStream.isRecording) {
      val folder = PathUtils.getRecordPath()
      if (!folder.exists()) folder.mkdir()
      val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
      recordPath = "${folder.absolutePath}/${sdf.format(Date())}.mp4"
      genericStream.startRecord(recordPath) { status ->
        if (status == RecordController.Status.RECORDING) {
          state(RecordController.Status.RECORDING)
        }
      }
      state(RecordController.Status.STARTED)
    } else {
      genericStream.stopRecord()
      state(RecordController.Status.STOPPED)
      PathUtils.updateGallery(this, recordPath)
    }
  }

  fun speakNow(text: String) {
    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "stream_countdown")
  }

  fun stopSpeech() {
    tts?.stop()
  }

  fun hasScreenCapture(): Boolean = mediaProjection != null

  fun startCapture(surfaceView: SurfaceView) {
    startPreview(surfaceView)
  }

  fun startStream(endpoint: String) {
    if (!genericStream.isStreaming) genericStream.startStream(endpoint)
  }

  fun startPreview(surfaceView: SurfaceView) {
    if (genericStream.isOnPreview || genericStream.isStreaming) return
    if (surfaceView.holder.surface.isValid) {
      genericStream.startPreview(surfaceView)
    }
  }

  fun stopPreview() {
    genericStream.stopPreview()
  }

  override fun onConnectionStarted(url: String) {
    callback?.onConnectionStarted(url)
  }

  override fun onConnectionSuccess() {
    callback?.onConnectionSuccess()
  }

  override fun onNewBitrate(bitrate: Long) {
    callback?.onNewBitrate(bitrate)
  }

  override fun onConnectionFailed(reason: String) {
    callback?.onConnectionFailed(reason)
  }

  override fun onDisconnect() {
    callback?.onDisconnect()
  }

  override fun onAuthError() {
    callback?.onAuthError()
  }

  override fun onAuthSuccess() {
    callback?.onAuthSuccess()
  }
}
