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
package com.pedro.streamer.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.tabs.TabLayout
import com.pedro.library.base.recording.RecordController
import com.pedro.streamer.R
import com.pedro.streamer.utils.fitAppPadding
import com.pedro.streamer.utils.toast

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class ScreenActivity : AppCompatActivity(), ScreenServiceCallback {

  enum class Action {
    STREAM, RECORD, NONE
  }

  companion object {
    private const val AUDIO_SOURCE_MIX_INDEX = 2
    const val TARGET_TWITCH = 0
    const val TARGET_YOUTUBE = 1
    const val TARGET_CUSTOM = 2
    const val TARGET_OMNISTREAM = 3
  }

  private lateinit var button: ImageView
  private lateinit var bRecord: ImageView
  private lateinit var bEvents: ImageView
  private lateinit var tvStreamLabel: TextView
  private lateinit var tvEventsLabel: TextView
  private lateinit var etUrl: EditText
  private lateinit var etStreamKey: EditText
  private lateinit var etOmniHost: EditText
  private lateinit var etChatChannel: EditText
  private lateinit var etYoutubeApiKey: EditText
  private lateinit var etYoutubeVideoId: EditText
  private lateinit var layoutYoutubeChat: View
  private lateinit var spTarget: Spinner
  private lateinit var spResolution: Spinner
  private lateinit var spAudioSource: Spinner
  private lateinit var swTts: SwitchCompat
  private lateinit var swSystemNotifications: SwitchCompat
  private lateinit var tvStatus: TextView
  private lateinit var tvStats: TextView
  private lateinit var tvNotificationStatus: TextView
  private lateinit var tvLog: TextView
  private lateinit var bShowPassword: ImageView
  private lateinit var bShowYoutubeApi: ImageView
  private lateinit var tabLayout: TabLayout
  private lateinit var tabSettings: View
  private lateinit var tabPreview: View
  private lateinit var tabLog: View
  private lateinit var surfaceView: SurfaceView
  private lateinit var tvCountdown: TextView
  private var isPasswordVisible = false
  private var isYoutubeApiVisible = false
  private var isLoadingPreferences = false
  private var selectedTargetIndex = 0
  private var isCountingDown = false
  private var countdownRemaining = 0
  private val mainHandler = Handler(Looper.getMainLooper())

  private var action = Action.NONE
  private val prefs by lazy { getSharedPreferences("xr_stream_prefs", Context.MODE_PRIVATE) }

  private val permissions = mutableListOf(
    Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA,
  ).apply {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      this.add(Manifest.permission.POST_NOTIFICATIONS)
    }
  }.toTypedArray()

  private val activityResultContract = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    val data = result.data
    if (data != null && result.resultCode == RESULT_OK) {
      val screenService = ScreenService.INSTANCE
      if (screenService != null) {
        if (screenService.prepareStream(result.resultCode, data)) {
          when (action) {
            Action.STREAM -> startStream()
            Action.RECORD -> toggleRecord()
            else -> {}
          }
        } else {
          toast("Prepare stream failed")
          showStartStreamButton()
        }
      }
    } else {
      toast("No permissions available")
      showStartStreamButton()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContentView(R.layout.activity_display)
    supportActionBar?.hide()
    fitAppPadding()

    initUi()
    loadPreferences()
    requestPermissions()

    val screenService = ScreenService.INSTANCE
    if (screenService == null) {
      startService(Intent(this, ScreenService::class.java))
    }
    screenService?.setCallback(this)

    updateStreamButtons()
  }

  private fun requestPermissions() {
    if (!hasPermissions(this)) {
      ActivityCompat.requestPermissions(this, permissions, 1)
    }
  }

  private fun hasPermissions(context: Context): Boolean {
    for (permission in permissions) {
      if (ActivityCompat.checkSelfPermission(context, permission)
        != PackageManager.PERMISSION_GRANTED
      ) {
        return false
      }
    }
    return true
  }

  private fun initUi() {
    button = findViewById(R.id.b_start_stop)
    bRecord = findViewById(R.id.b_record)
    bEvents = findViewById(R.id.b_events)
    tvStreamLabel = findViewById(R.id.tv_stream_label)
    tvEventsLabel = findViewById(R.id.tv_events_label)
    etUrl = findViewById(R.id.et_rtp_url)
    etStreamKey = findViewById(R.id.et_stream_key)
    etOmniHost = findViewById(R.id.et_omnistream_host)
    etChatChannel = findViewById(R.id.et_chat_channel)
    etYoutubeApiKey = findViewById(R.id.et_youtube_api_key)
    etYoutubeVideoId = findViewById(R.id.et_youtube_video_id)
    layoutYoutubeChat = findViewById(R.id.layout_youtube_chat)
    spTarget = findViewById(R.id.sp_target)
    spResolution = findViewById(R.id.sp_resolution)
    spAudioSource = findViewById(R.id.sp_audio_source)
    swTts = findViewById(R.id.sw_tts)
    swSystemNotifications = findViewById(R.id.sw_system_notifications)
    tvStatus = findViewById(R.id.tv_status)
    tvStats = findViewById(R.id.tv_stats)
    tvNotificationStatus = findViewById(R.id.tv_notification_status)
    tvLog = findViewById(R.id.tv_log)
    bShowPassword = findViewById(R.id.b_show_password)
    bShowYoutubeApi = findViewById(R.id.b_show_youtube_api)
    tabLayout = findViewById(R.id.tab_layout)
    tabSettings = findViewById(R.id.tab_settings)
    tabPreview = findViewById(R.id.tab_preview)
    tabLog = findViewById(R.id.tab_log)
    surfaceView = findViewById(R.id.surfaceView)
    tvCountdown = findViewById(R.id.tv_countdown)

    tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
      override fun onTabSelected(tab: TabLayout.Tab?) {
        tabSettings.visibility = View.GONE
        tabPreview.visibility = View.GONE
        tabLog.visibility = View.GONE
        
        when (tab?.position) {
          0 -> {
            tabSettings.visibility = View.VISIBLE
            ScreenService.INSTANCE?.stopPreview()
          }
          1 -> {
            tabPreview.visibility = View.VISIBLE
            tabPreview.post {
              ScreenService.INSTANCE?.startPreview(surfaceView)
            }
          }
          2 -> {
            tabLog.visibility = View.VISIBLE
            ScreenService.INSTANCE?.stopPreview()
          }
        }
      }
      override fun onTabUnselected(tab: TabLayout.Tab?) {}
      override fun onTabReselected(tab: TabLayout.Tab?) {}
    })

    bShowPassword.setOnClickListener {
      isPasswordVisible = !isPasswordVisible
      if (isPasswordVisible) {
        etStreamKey.transformationMethod = null
      } else {
        etStreamKey.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
      }
      etStreamKey.setSelection(etStreamKey.text.length)
    }

    bShowYoutubeApi.setOnClickListener {
      isYoutubeApiVisible = !isYoutubeApiVisible
      if (isYoutubeApiVisible) {
        etYoutubeApiKey.transformationMethod = null
      } else {
        etYoutubeApiKey.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
      }
      etYoutubeApiKey.setSelection(etYoutubeApiKey.text.length)
    }

    spTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        updateTargetFields(position)
        if (!isLoadingPreferences) {
          if (selectedTargetIndex != position) {
            prefs.edit().putString(streamKeyPref(selectedTargetIndex), etStreamKey.text.toString()).apply()
            selectedTargetIndex = position
            etStreamKey.setText(storedStreamKey(position))
          }
          updateResolutionOptions(spResolution.selectedItemPosition)
          savePreferences()
        }
      }
      override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    spResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        if (isLoadingPreferences) return
        ScreenService.INSTANCE?.setStreamResolution(position)
        savePreferences()
      }
      override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    spAudioSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        if (isLoadingPreferences) return
        ScreenService.INSTANCE?.toggleAudioSource(audioSourceId(position))
        savePreferences()
      }
      override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    listOf(etStreamKey, etUrl, etOmniHost, etChatChannel, etYoutubeApiKey, etYoutubeVideoId).forEach { field ->
      field.doAfterTextChanged { if (!isLoadingPreferences) savePreferences() }
    }

    swTts.setOnCheckedChangeListener { _, isChecked ->
      if (isLoadingPreferences) return@setOnCheckedChangeListener
      ScreenService.INSTANCE?.setTtsEnabled(isChecked)
      updateNotificationStatusLabel()
      savePreferences()
    }

    swSystemNotifications.setOnCheckedChangeListener { _, isChecked ->
      if (isLoadingPreferences) return@setOnCheckedChangeListener
      ScreenService.INSTANCE?.setSystemNotificationsEnabled(isChecked)
      updateNotificationStatusLabel()
      savePreferences()
    }

    button.setOnClickListener {
      val service = ScreenService.INSTANCE
      if (service != null) {
        service.setCallback(this)
        if (isCountingDown) {
          cancelCountdown()
        } else if (!service.isStreaming() && !service.hasScreenCapture()) {
          action = Action.STREAM
          showStopStreamButton()
          activityResultContract.launch(service.sendIntent())
        } else if (!service.isStreaming()) {
          startStream()
        } else {
          stopStream()
        }
      }
    }

    bRecord.setOnClickListener {
      val service = ScreenService.INSTANCE
      if (service != null) {
        service.setCallback(this)
        if (!service.isStreaming() && !service.isRecording()) {
          action = Action.RECORD
          activityResultContract.launch(service.sendIntent())
        } else toggleRecord()
      }
    }

    bEvents.setOnClickListener {
      val service = ScreenService.INSTANCE
      if (service == null) {
        startService(Intent(this, ScreenService::class.java))
        toast("Starting service, tap again")
        return@setOnClickListener
      }
      service.setCallback(this)
      if (service.isListeningToEvents()) {
        service.stopChatListener()
        updateEventsButton()
      } else {
        startEventListener()
      }
    }
  }

  private fun updateTargetFields(position: Int) {
    etUrl.visibility = if (position == TARGET_CUSTOM) View.VISIBLE else View.GONE
    etOmniHost.visibility = if (position == TARGET_OMNISTREAM) View.VISIBLE else View.GONE
    layoutYoutubeChat.visibility = if (position == TARGET_YOUTUBE) View.VISIBLE else View.GONE
    etChatChannel.visibility = if (position == TARGET_TWITCH || position == TARGET_CUSTOM) View.VISIBLE else View.GONE
    etStreamKey.hint = if (position == TARGET_OMNISTREAM) "OmniStream ingest key" else "Stream Key"
  }

  private fun updateResolutionOptions(preferredIndex: Int) {
    val allResolutions = resources.getStringArray(R.array.resolutions)
    val resolutionOptions = if (isResolutionLimitedTarget(spTarget.selectedItemPosition)) {
      allResolutions.take(2)
    } else {
      allResolutions.toList()
    }
    val selectedIndex = preferredIndex.coerceIn(0, resolutionOptions.lastIndex)
    val resolutionListener = spResolution.onItemSelectedListener
    spResolution.onItemSelectedListener = null
    spResolution.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutionOptions).apply {
      setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
    spResolution.setSelection(selectedIndex)
    spResolution.onItemSelectedListener = resolutionListener
    ScreenService.INSTANCE?.setStreamResolution(selectedIndex)
    if (!isLoadingPreferences) savePreferences()
  }

  private fun updateNotificationStatusLabel() {
    tvNotificationStatus.text = when {
      swTts.isChecked && swSystemNotifications.isChecked -> "Notifications Status: TTS + System"
      swTts.isChecked -> "Notifications Status: TTS only"
      swSystemNotifications.isChecked -> "Notifications Status: System only"
      else -> "Notifications Status: Off"
    }
    tvNotificationStatus.setTextColor(Color.WHITE)
  }

  private fun audioSourceId(position: Int) = when (position) {
    0 -> R.id.audio_source_microphone
    1 -> R.id.audio_source_internal
    else -> R.id.audio_source_mix
  }

  private fun isResolutionLimitedTarget(targetIndex: Int) =
    targetIndex == TARGET_TWITCH

  private fun streamKeyPref(targetIndex: Int) = "stream_key_$targetIndex"

  private fun storedStreamKey(targetIndex: Int): String {
    val perTargetKey = prefs.getString(streamKeyPref(targetIndex), null)
    if (perTargetKey != null) return perTargetKey
    return if (targetIndex == prefs.getInt("target", 0)) prefs.getString("stream_key", "") ?: "" else ""
  }

  private fun loadPreferences() {
    isLoadingPreferences = true
    selectedTargetIndex = prefs.getInt("target", 0)
    spTarget.setSelection(selectedTargetIndex)
    updateTargetFields(selectedTargetIndex)
    etStreamKey.setText(storedStreamKey(selectedTargetIndex))
    etUrl.setText(prefs.getString("custom_url", ""))
    etOmniHost.setText(prefs.getString("omnistream_host", ""))
    etChatChannel.setText(prefs.getString("chat_channel", ""))
    etYoutubeApiKey.setText(prefs.getString("youtube_api_key", ""))
    etYoutubeVideoId.setText(prefs.getString("youtube_video_id", ""))
    updateResolutionOptions(prefs.getInt("resolution_pos", 0))
    spAudioSource.setSelection(prefs.getInt("audio_pos", AUDIO_SOURCE_MIX_INDEX))
    swTts.isChecked = prefs.getBoolean("tts_enabled", false)
    swSystemNotifications.isChecked = prefs.getBoolean("system_notifications_enabled", true)
    isLoadingPreferences = false

    ScreenService.INSTANCE?.let { service ->
      service.setStreamResolution(spResolution.selectedItemPosition)
      service.toggleAudioSource(audioSourceId(spAudioSource.selectedItemPosition))
      service.setTtsEnabled(swTts.isChecked)
      service.setSystemNotificationsEnabled(swSystemNotifications.isChecked)
    }
    updateNotificationStatusLabel()
  }

  private fun savePreferences() {
    prefs.edit().apply {
      putInt("target", spTarget.selectedItemPosition)
      putString(streamKeyPref(spTarget.selectedItemPosition), etStreamKey.text.toString())
      putString("custom_url", etUrl.text.toString())
      putString("omnistream_host", etOmniHost.text.toString())
      putInt("resolution_pos", spResolution.selectedItemPosition)
      putInt("audio_pos", spAudioSource.selectedItemPosition)
      putBoolean("tts_enabled", swTts.isChecked)
      putBoolean("system_notifications_enabled", swSystemNotifications.isChecked)
      putString("chat_channel", etChatChannel.text.toString())
      putString("youtube_api_key", etYoutubeApiKey.text.toString())
      putString("youtube_video_id", etYoutubeVideoId.text.toString())
      apply()
    }
  }

  private fun updateStreamButtons() {
    val screenService = ScreenService.INSTANCE
    val streamActive = isCountingDown || (screenService != null && screenService.isStreaming())
    if (streamActive) {
      showStopStreamButton()
    } else {
      showStartStreamButton()
    }
    if (screenService != null && screenService.isRecording()) {
      bRecord.setImageResource(R.drawable.stop_icon)
    } else {
      bRecord.setImageResource(R.drawable.record_icon)
    }
    spResolution.isEnabled = !isCountingDown && (screenService == null || (!screenService.isStreaming() && !screenService.isRecording()))
    updateEventsButton()
  }

  private fun updateEventsButton() {
    val listening = ScreenService.INSTANCE?.isListeningToEvents() == true
    if (listening) {
      bEvents.setImageResource(R.drawable.stream_stop_icon)
      tvEventsLabel.text = "Disconnect from Events"
    } else {
      bEvents.setImageResource(R.drawable.sync_icon)
      tvEventsLabel.text = "Connect to Events"
    }
  }

  private fun showStopStreamButton() {
    button.setImageResource(R.drawable.stream_stop_icon)
    tvStreamLabel.text = "Stop Stream"
  }

  private fun showStartStreamButton() {
    button.setImageResource(R.drawable.stream_icon)
    tvStreamLabel.text = "Stream"
  }

  private fun startStream() {
    savePreferences()
    val streamKey = etStreamKey.text.toString()
    val targetPos = spTarget.selectedItemPosition
    val omniHost = normalizeOmniStreamHost(etOmniHost.text.toString())
    val endpoint = when (targetPos) {
      TARGET_TWITCH -> "rtmps://live.twitch.tv:443/app/$streamKey"
      TARGET_YOUTUBE -> "rtmp://a.rtmp.youtube.com/live2/$streamKey"
      TARGET_OMNISTREAM -> "rtmp://$omniHost:1935/live/$streamKey"
      else -> etUrl.text.toString()
    }

    val missingInput = when (targetPos) {
      TARGET_CUSTOM -> endpoint.isBlank()
      TARGET_OMNISTREAM -> omniHost.isBlank() || streamKey.isBlank()
      else -> streamKey.isBlank()
    }
    if (missingInput) {
      toast(if (targetPos == TARGET_OMNISTREAM) "Please enter OmniStream host and stream key" else "Please enter stream key / URL")
      showStartStreamButton()
      return
    }

    isCountingDown = true
    showStopStreamButton()
    updateStreamButtons()

    if (tabLayout.selectedTabPosition != 1) {
      tabLayout.getTabAt(1)?.select()
    }
    tabPreview.post {
      ScreenService.INSTANCE?.startCapture(surfaceView)
      beginCountdown(endpoint)
    }
  }

  private fun beginCountdown(endpoint: String) {
    isCountingDown = true
    countdownRemaining = 5
    updateStreamButtons()
    runCountdownTick(endpoint)
  }

  private fun runCountdownTick(endpoint: String) {
    if (!isCountingDown) return
    if (countdownRemaining > 0) {
      tvCountdown.text = countdownRemaining.toString()
      tvCountdown.visibility = View.VISIBLE
      tvStatus.text = "Starting in $countdownRemaining..."
      ScreenService.INSTANCE?.speakNow(countdownRemaining.toString())
      countdownRemaining--
      mainHandler.postDelayed({ runCountdownTick(endpoint) }, 1000)
    } else {
      tvCountdown.visibility = View.GONE
      isCountingDown = false
      ScreenService.INSTANCE?.speakNow("stream started")
      connectStream(endpoint)
    }
  }

  private fun cancelCountdown() {
    isCountingDown = false
    countdownRemaining = 0
    mainHandler.removeCallbacksAndMessages(null)
    tvCountdown.visibility = View.GONE
    ScreenService.INSTANCE?.stopSpeech()
    tvStatus.text = "Status: Disconnected"
    tvStatus.setTextColor(Color.WHITE)
    updateStreamButtons()
  }

  private fun connectStream(endpoint: String) {
    showStopStreamButton()
    ScreenService.INSTANCE?.startStream(endpoint)
    startEventListener()
    updateStreamButtons()
  }

  private fun startEventListener() {
    savePreferences()
    val service = ScreenService.INSTANCE
    if (service == null) {
      toast("Service not ready")
      return
    }
    if (service.isListeningToEvents()) {
      updateEventsButton()
      return
    }
    val targetPos = spTarget.selectedItemPosition
    when (targetPos) {
      TARGET_OMNISTREAM -> {
        val omniHost = normalizeOmniStreamHost(etOmniHost.text.toString())
        if (omniHost.isBlank()) {
          toast("Please enter OmniStream host")
          return
        }
        service.startOmniStreamListener(omniHost)
      }
      TARGET_YOUTUBE -> {
        val apiKey = etYoutubeApiKey.text.toString()
        val videoId = etYoutubeVideoId.text.toString()
        if (apiKey.isBlank() || videoId.isBlank()) {
          toast("Please enter YouTube API key and video ID")
          return
        }
        service.startYouTubeChatListener(videoId, apiKey)
      }
      else -> {
        val channel = etChatChannel.text.toString().trim().removePrefix("#")
        if (channel.isBlank()) {
          toast("Please enter Twitch channel name")
          return
        }
        service.startChatListener(channel)
      }
    }
    updateEventsButton()
  }

  private fun stopStream() {
    cancelCountdown()
    showStartStreamButton()
    ScreenService.INSTANCE?.stopStream()
    tvStatus.text = "Status: Disconnected"
    tvStatus.setTextColor(Color.WHITE)
    tvStats.text = "0 kbps | 0 fps"
    tvStats.setTextColor(Color.WHITE)
    updateStreamButtons()
  }

  private fun normalizeOmniStreamHost(rawHost: String): String {
    var host = rawHost.trim()
    listOf("http://", "https://", "rtmp://", "rtmps://", "ws://", "wss://").forEach { prefix ->
      if (host.startsWith(prefix, ignoreCase = true)) host = host.substring(prefix.length)
    }
    host = host.substringBefore("/")
    if (host.startsWith("[")) return host.substringAfter("[").substringBefore("]")
    val lastColon = host.lastIndexOf(':')
    if (lastColon > 0 && host.substring(lastColon + 1).all { it.isDigit() }) {
      host = host.substring(0, lastColon)
    }
    return host.trim()
  }

  private fun toggleRecord() {
    ScreenService.INSTANCE?.toggleRecord { state ->
      when (state) {
        RecordController.Status.STARTED -> {
          bRecord.setImageResource(R.drawable.pause_icon)
          spResolution.isEnabled = false
        }
        RecordController.Status.STOPPED -> {
          bRecord.setImageResource(R.drawable.record_icon)
          updateStreamButtons()
        }
        RecordController.Status.RECORDING -> {
          bRecord.setImageResource(R.drawable.stop_icon)
          spResolution.isEnabled = false
        }
        else -> {}
      }
    }
  }

  override fun onPause() {
    super.onPause()
    savePreferences()
  }

  override fun onDestroy() {
    super.onDestroy()
    cancelCountdown()
    savePreferences()
    val screenService = ScreenService.INSTANCE
    if (screenService != null && !screenService.isStreaming() && !screenService.isRecording() && !screenService.isListeningToEvents()) {
      screenService.setCallback(null)
      if (isFinishing) stopService(Intent(this, ScreenService::class.java))
    }
  }

  override fun onConnectionStarted(url: String) {
    tvStatus.text = "Status: Connecting..."
  }

  override fun onConnectionSuccess() {
    runOnUiThread {
      toast("Connected")
      tvStatus.text = "Status: Connected"
      tvStatus.setTextColor(Color.GREEN)
      tvStats.setTextColor(Color.GREEN)
    }
  }

  override fun onConnectionFailed(reason: String) {
    runOnUiThread {
      stopStream()
      toast("Failed: $reason")
      tvStatus.text = "Status: Failed"
      tvStatus.setTextColor(Color.WHITE)
      tvStats.setTextColor(Color.WHITE)
    }
  }

  override fun onNewBitrate(bitrate: Long) {
    runOnUiThread {
      tvStats.text = "${bitrate / 1000} kbps"
    }
  }

  override fun onDisconnect() {
    runOnUiThread {
      toast("Disconnected")
      tvStatus.text = "Status: Disconnected"
      tvStatus.setTextColor(Color.WHITE)
      tvStats.setTextColor(Color.WHITE)
    }
  }

  override fun onAuthError() {
    runOnUiThread {
      stopStream()
      toast("Auth error")
    }
  }

  override fun onAuthSuccess() {
    runOnUiThread {
      toast("Auth success")
    }
  }

  override fun onNotificationStatusChange(enabled: Boolean) {
    runOnUiThread {
      tvNotificationStatus.text = if (enabled) "Notifications Status: Active" else "Notifications Status: Error"
      tvNotificationStatus.setTextColor(if (enabled) Color.GREEN else Color.RED)
      updateEventsButton()
    }
  }

  override fun onEventsListeningChange(listening: Boolean) {
    runOnUiThread { updateEventsButton() }
  }

  override fun onLogMessage(message: String) {
    runOnUiThread {
      tvLog.append("$message\n")
    }
  }
}
