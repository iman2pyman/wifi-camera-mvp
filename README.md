# WiFi Camera

Android-to-Android live camera streaming over a direct local Wi-Fi network. No router, internet connection, or cloud relay is required.

## Connection design

- Android 8.0 (API 26) and newer: the sender requests Android's Local-Only Hotspot API. This creates a nearby Wi-Fi network without internet. The app displays its SSID, passphrase, and a Wi-Fi QR code.
- Android 6.0–7.1 (API 23–25): the app provides a manual hotspot-settings fallback because the Local-Only Hotspot API is unavailable on those releases.
- The receiver joins the sender's Wi-Fi network, then enters the sender's local address in the receiver screen to view the live camera stream.
- The app does not require a shared router or mobile data.

## Build

GitHub Actions builds a debug APK on pushes to main. Open Actions, select the latest successful **Build WiFi Camera APK** run, and download the `wifi-camera-debug-apk` artifact.

## Test steps

1. Install the same APK on two Android phones.
2. On phone A, choose **ارسال تصویر** and approve camera and nearby Wi-Fi permissions.
3. Wait for the local network and QR code. On phone B, scan the Wi-Fi QR code (or join the shown network manually).
4. On phone A, note the local server address. On phone B, choose **دریافت تصویر**, enter that address, and connect.
5. Keep phone A's screen/app active while streaming. Stop sending to release the hotspot.

## Limitations and security

- Android, phone manufacturers, carrier settings, and hardware may restrict local-only hotspot creation. The app includes a manual hotspot fallback, but the exact steps vary by device.
- Android 6/7 use the manual fallback; automatic hotspot credentials and QR generation are not available there.
- This MVP streams video frames only; no audio, background camera operation, or internet relay.
- The local HTTP stream currently has no app-level authentication or encryption. Only share the Wi-Fi password with trusted people and stop streaming when finished.
- A successful cloud build does not replace testing on at least two physical Android devices from different manufacturers.
