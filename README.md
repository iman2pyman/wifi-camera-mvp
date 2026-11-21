# WiFi Camera

A lightweight Android app for streaming one phone's camera to another phone on the same Wi-Fi network.

## Current MVP

- Sender mode: requests camera permission, shows a camera preview, and starts a local MJPEG server.
- Viewer mode: enter the sender phone's local IP address and view the live stream.
- The sender remains visible and active while streaming; closing the app stops the camera/server.
- No cloud relay, remote internet access, audio, or background camera operation in this version.

## Build

GitHub Actions builds a debug APK on every push to `main` and on manual workflow dispatch.

Open **Actions → Build WiFi Camera APK → latest successful run → Artifacts** and download `wifi-camera-debug-apk`.

## Use

1. Connect both phones to the same trusted Wi-Fi network.
2. On the sender phone, open the app, choose **ارسال تصویر**, and grant camera permission.
3. Keep the app open. Share the displayed address (for example, `http://192.168.1.12:8080`) with the viewer phone.
4. On the viewer phone, choose **دریافت تصویر**, enter the address, and tap Connect.
5. Tap Stop on the sender when finished.

## Security and limitations

This first MVP has no authentication or encryption. Use only on a trusted private Wi-Fi network. Do not expose port 8080 to the public internet. The stream is accessible to devices that can reach the sender's local network address.

This project is an initial implementation and must pass a real GitHub Actions build and two-device test before being treated as release-ready.
