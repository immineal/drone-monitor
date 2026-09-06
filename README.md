# Drone Monitor

A small Android app that shows the live camera feed from a cheap "VS GPS" style WiFi quadcopter, without the vendor app.

The stock app (`com.vison.macrochip.gps.pro`) asks for around thirty permissions on first launch (location, phone state, storage, audio, and more) for what amounts to a video window. This app does the same job with four permissions, no telemetry, and no accounts. It joins the drone's WiFi, speaks the drone's protocol, and draws the H.264 stream on screen.

## What it does

- Live video from the drone camera, decoded in hardware and drawn at full frame rate.
- A monitor first: it fills the screen with the picture and stays out of the way.

The screen has camera-tilt buttons wired up, but the gimbal command for this drone isn't found yet, so they do nothing on this unit for now.

It deliberately does **not** control the aircraft. None of the flight commands (throttle, arm, takeoff, land) are implemented, and the one byte prefix that carries them is never sent. This is a camera monitor, not a controller.

## How it works

The drone runs its own open WiFi access point and a small Linux stack (Allwinner sun8i, hardware H.264 encoder). Once the phone joins that AP:

1. The app finds the drone at the WiFi gateway address (`172.16.10.1`).
2. It opens a TCP connection to port 8888 and sends the drone's stream-request bytes once a second. That heartbeat is what keeps the video flowing; miss it and the drone pauses the stream.
3. The drone answers with a continuous byte stream: a 44-byte header per frame, then raw H.264 (Annex-B, with parameter sets in-band on every I-frame).
4. The app strips the headers, feeds each frame to `MediaCodec`, and renders straight to a `SurfaceView`.

Camera tilt is meant to be a one-line command on UDP port 8080 (`FF 53 54 20 01 <angle 0-90>`), but this unit ignores it, so the real gimbal command is still being tracked down.

The protocol was recovered by decompiling the vendor app and reading the drone's own firmware over its (root, no-password) telnet shell. Notes are under [`notes/`](../notes) in the working tree if you have them.

## Build and install

Needs JDK 17 and an Android SDK (build-tools 34+, platform 35).

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then join the drone's WiFi on the phone and open the app.

## Permissions, and why each one is here

| Permission | Reason |
|---|---|
| `INTERNET` | Open the TCP/UDP sockets to the drone. |
| `ACCESS_NETWORK_STATE` | Find the drone's WiFi network and bind traffic to it. |
| `ACCESS_WIFI_STATE` | Read the WiFi gateway address (the drone's IP). |
| `CHANGE_NETWORK_STATE` | Pin the app's sockets to the WiFi link, which has no internet route. |

No location, no camera, no microphone, no storage, no phone state.

## Compatibility

Written against one specific drone: a VISON-protocol unit whose AP reports transport TCP and codec H.264 at 1280x720. Other units in the same app family use different chips (HiSilicon, GK, "872") and may need small changes to the codec or framing. The connect flow (join AP, talk to the gateway) is the same across the family.

## Status

Live video runs with no stalls. Still open: the gimbal command for this unit, longer link range, and lower latency.
