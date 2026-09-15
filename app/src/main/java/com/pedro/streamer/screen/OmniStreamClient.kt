/*
 * Copyright (C) 2026 BlueScorpioVR (OmnistreamXr).
 *
 * Based on RootEncoder by pedroSG94:
 * https://github.com/pedroSG94/RootEncoder
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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PlatformViewerCount(val label: String, val viewerCount: Int)

class OmniStreamClient(
  private val scope: CoroutineScope,
  private val callback: Callback,
) {
  interface Callback {
    fun onLog(message: String)
    fun onChatMessage(author: String, message: String)
    fun onAlert(title: String, summary: String)
    fun onViewerCountsChanged(total: Int, perPlatform: List<PlatformViewerCount>)
    fun onStatusChange(connected: Boolean)
  }

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

  private val webSocketClient = httpClient.newBuilder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .build()

  private var running = false
  private var host = ""
  private var webSocket: WebSocket? = null
  private var socketGeneration = 0
  private var reconnectJob: Job? = null
  private var pingJob: Job? = null
  private var pollJob: Job? = null
  private var lastViewerTotal: Int? = null
  private var loggedPollError = false

  fun isRunning(): Boolean = running

  fun start(omniHost: String) {
    stop()
    host = omniHost
    running = true
    callback.onLog("OmniStream: Connecting ws://$host:3000/ws")
    callback.onLog("OmniStream: Polling viewers at http://$host:3000/api/v1/twitch-compat/helix/streams")
    openWebSocket()
    startViewerPolling()
  }

  fun stop() {
    running = false
    socketGeneration++
    reconnectJob?.cancel()
    pingJob?.cancel()
    pollJob?.cancel()
    reconnectJob = null
    pingJob = null
    pollJob = null
    webSocket?.close(1000, "stop")
    webSocket = null
    lastViewerTotal = null
    loggedPollError = false
  }

  private fun openWebSocket() {
    if (!running) return
    val generation = ++socketGeneration
    webSocket?.cancel()
    val request = Request.Builder()
      .url("ws://$host:3000/ws")
      .build()
    webSocket = webSocketClient.newWebSocket(request, object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        if (generation != socketGeneration) return
        callback.onLog("OmniStream: Events WebSocket connected")
        callback.onStatusChange(true)
        startJsonPing(webSocket)
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        if (generation != socketGeneration) return
        handleMessage(text)
      }

      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (generation != socketGeneration) return
        pingJob?.cancel()
        callback.onStatusChange(false)
        if (running) {
          callback.onLog("OmniStream: Events WebSocket closed, reconnecting in 3s")
          scheduleReconnect()
        }
      }

      override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
        if (generation != socketGeneration) return
        pingJob?.cancel()
        callback.onStatusChange(false)
        if (running) {
          callback.onLog("OmniStream: Events WebSocket error: ${throwable.message}")
          scheduleReconnect()
        }
      }
    })
  }

  private fun startJsonPing(socket: WebSocket) {
    pingJob?.cancel()
    pingJob = scope.launch {
      while (isActive && running) {
        delay(20_000)
        if (running) socket.send("""{"type":"ping"}""")
      }
    }
  }

  private fun scheduleReconnect() {
    reconnectJob?.cancel()
    reconnectJob = scope.launch {
      delay(3_000)
      if (running) openWebSocket()
    }
  }

  private fun handleMessage(text: String) {
    val json = try {
      JSONObject(text)
    } catch (_: Exception) {
      return
    }
    val type = json.optString("type")
    val payload = json.optJSONObject("payload") ?: json
    when (type) {
      "connection_established" -> {
        callback.onLog("OmniStream: ${json.optString("message").ifBlank { "Connected" }}")
      }
      "chat_message" -> handleChat(payload)
      "notification_event" -> handleAlert(payload)
      "pong", "ping", "ffmpeg_log", "relay_status", "ingest_status", "ingest_telemetry" -> {}
    }
  }

  private fun handleChat(payload: JSONObject) {
    val author = payload.optString("author").ifBlank { "Unknown" }
    val platform = payload.optString("platform")
    val message = payload.optString("message")
    val donation = payload.optJSONObject("donation")
    val donationText = if (donation != null) {
      val amount = donation.optString("amount")
      val currency = donation.optString("currency")
      val donationType = donation.optString("type")
      " ($donationType $amount $currency)".trimEnd()
    } else {
      ""
    }
    val displayAuthor = if (platform.isBlank()) author else "$author ($platform)"
    callback.onChatMessage(displayAuthor, message + donationText)
  }

  private fun handleAlert(payload: JSONObject) {
    val title = payload.optString("title").ifBlank {
      payload.optJSONObject("user")?.optString("displayName")?.takeIf { name -> name.isNotBlank() }
        ?: payload.optString("eventType").ifBlank { "OmniStream alert" }
    }
    val summary = payload.optString("summary")
    callback.onAlert(title, summary)
  }

  private fun startViewerPolling() {
    pollJob?.cancel()
    pollJob = scope.launch {
      while (isActive && running) {
        pollViewerCounts()
        delay(7_000)
      }
    }
  }

  private fun pollViewerCounts() {
    val request = Request.Builder()
      .url("http://$host:3000/api/v1/twitch-compat/helix/streams")
      .get()
      .build()
    try {
      httpClient.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          logPollError("HTTP ${response.code}")
          return
        }
        val json = JSONObject(body)
        val streams = json.optJSONArray("data") ?: JSONArray()
        var total = 0
        val labeledStreams = mutableListOf<Pair<String, Int>>()
        for (index in 0 until streams.length()) {
          val stream = streams.getJSONObject(index)
          val viewers = stream.optInt("viewer_count", 0)
          total += viewers
          labeledStreams.add(destinationLabel(stream) to viewers)
        }
        val perPlatform = resolveDestinationLabels(labeledStreams)
        loggedPollError = false
        val previousTotal = lastViewerTotal
        lastViewerTotal = total
        if (previousTotal == null) {
          if (total > 0) callback.onViewerCountsChanged(total, perPlatform)
        } else if (previousTotal != total) {
          callback.onViewerCountsChanged(total, perPlatform)
        }
      }
    } catch (error: Exception) {
      logPollError(error.message ?: "poll failed")
    }
  }

  private fun resolveDestinationLabels(
    labeledStreams: List<Pair<String, Int>>,
  ): List<PlatformViewerCount> {
    if (labeledStreams.size != 2) {
      return labeledStreams.map { (label, viewers) -> PlatformViewerCount(label, viewers) }
    }
    val firstLabel = labeledStreams[0].first
    val secondLabel = labeledStreams[1].first
    val firstViewers = labeledStreams[0].second
    val secondViewers = labeledStreams[1].second
    val resolved = when {
      firstLabel != secondLabel && firstLabel != "Unknown" && secondLabel != "Unknown" ->
        labeledStreams
      firstLabel != "Unknown" ->
        listOf(firstLabel to firstViewers, otherDestination(firstLabel) to secondViewers)
      secondLabel != "Unknown" ->
        listOf(otherDestination(secondLabel) to firstViewers, secondLabel to secondViewers)
      else ->
        listOf("Twitch" to firstViewers, "YouTube" to secondViewers)
    }
    return resolved.map { (label, viewers) -> PlatformViewerCount(label, viewers) }
  }

  private fun otherDestination(label: String) = if (label == "Twitch") "YouTube" else "Twitch"

  private fun destinationLabel(stream: JSONObject): String {
    val skippedKeys = setOf("user_name", "user_login", "title", "game_name", "game_id", "started_at", "language", "type")
    val keys = stream.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      if (key in skippedKeys) continue
      namedDestination(streamValue(stream, key))?.let { destination -> return destination }
    }
    val tags = stream.optJSONArray("tags")
    if (tags != null) {
      for (index in 0 until tags.length()) {
        namedDestination(tags.optString(index))?.let { destination -> return destination }
      }
    }
    val userId = stream.optString("user_id")
    if (userId.startsWith("UC") && userId.length >= 22) return "YouTube"
    if (userId.isNotBlank() && userId.all { character -> character.isDigit() }) return "Twitch"
    val streamId = stream.optString("id")
    if (streamId.matches(Regex("^[A-Za-z0-9_-]{11}$")) && streamId.any { character -> character.isLetter() }) {
      return "YouTube"
    }
    if (streamId.isNotBlank() && streamId.all { character -> character.isDigit() }) return "Twitch"
    return "Unknown"
  }

  private fun streamValue(stream: JSONObject, key: String): String {
    val nested = stream.optJSONObject(key)
    if (nested != null) {
      return nested.optString("name").ifBlank { nested.optString("id") }
    }
    return stream.optString(key)
  }

  private fun namedDestination(value: String): String? {
    val normalized = value.lowercase()
    return when {
      normalized.contains("youtube") || normalized.contains("ytimg") -> "YouTube"
      normalized.contains("twitch") || normalized.contains("jtvnw") || normalized.contains("ttvnw") -> "Twitch"
      else -> null
    }
  }

  private fun logPollError(reason: String) {
    if (loggedPollError) return
    loggedPollError = true
    callback.onLog("OmniStream: Viewer poll error: $reason")
  }
}
