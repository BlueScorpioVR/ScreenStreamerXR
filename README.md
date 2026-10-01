# Screen Stream XR

Android XR app that captures the headset display and publishes it over RTMP. Chat, alerts, and viewer counts can stay connected even when the headset is not encoding.

> **Beta (v0.1.0):** this is an early release. Expect rough edges, and settings or behavior may change between versions. Bug reports are welcome.

## Destinations

- **Twitch** — RTMP ingest plus Twitch chat
- **YouTube** — RTMP ingest plus YouTube live chat
- **Custom URL** — any RTMP endpoint
- **OmniStream** — RTMP ingest to `rtmp://<host>:1935/live/<stream-key>`, with events from the OmniStream WebSocket and viewer counts from its Helix-compatible API. OmniStream relays to Twitch (1080p60 H.264) and YouTube (passthrough).

Host and stream key (and Twitch/YouTube chat settings) are saved on the device.

## App controls

The launcher is a single screen with Settings, Preview, and Log tabs.

- **Record** — capture the display to a local movie file (`Movies/ScreenStreamerXR`)
- **Connect to Events** — attach chat, alerts, and viewer notifications without starting encode. Starting a stream also connects events automatically. Stopping the stream leaves events connected until you disconnect them.
- **Stream** — MediaProjection capture, encode, and publish to the selected destination

Optional toggles:

- **TTS** — speak chat and alerts
- **System notifications** — post Android notifications for chat, alerts, and viewer-count changes (Twitch and YouTube labeled separately)

## Build

Open the project in Android Studio and run the `app` module, or:

```bash
./gradlew :app:assembleDebug
```

Install the debug APK on an Android XR headset (for example via wireless ADB). minSdk is 23 (Android 6.0).

## License

This repository is a derivative of [RootEncoder](https://github.com/pedroSG94/RootEncoder) by [pedroSG94](https://github.com/pedroSG94) and contributors. RootEncoder remains the copyrighted work of its authors and is licensed under the [Apache License 2.0](LICENSE.txt). See [NOTICE](NOTICE) for attribution.

Modifications for Screen Stream XR (OmniStream ingest, events, viewer counts, TTS, and notifications) are by [BlueScorpioVR](https://github.com/BlueScorpioVR).
