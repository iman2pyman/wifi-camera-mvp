# دوربین کنترل کودک | Baby Monitor Camera

An Android-to-Android baby monitor camera. The sender phone stays near the child; the parent phone connects directly over local Wi-Fi. No router, internet, or cloud relay is required.

## Brand and interface

- Persian app name: **دوربین کنترل کودک**
- English product name: **Baby Monitor Camera**
- Soft nursery palette: sky blue, white, and gentle pink.
- Rounded elevated controls, baby-friendly iconography, and a baby + camera launcher icon.

## Connection design

- Android 8.0 (API 26) and newer: the sender requests Android's Local-Only Hotspot API. This creates a nearby Wi-Fi network without internet. The app displays its SSID, passphrase, and a Wi-Fi QR code.
- Android 6.0–7.1 (API 23–25): the app provides a manual hotspot-settings fallback.
- The receiver joins the sender's Wi-Fi network, then enters the sender's local address in the receiver screen to view the live camera stream.
- The app does not require a shared router or mobile data.

## Build

GitHub Actions builds a debug APK on pushes to main. Open Actions, select the latest successful **Build WiFi Camera APK** run, and download the `wifi-camera-debug-apk` artifact.

## Test steps

1. Install the same APK on two Android phones.
2. On the phone near the child, choose **گوشی کنار کودک — ارسال تصویر** and approve camera and nearby Wi-Fi permissions.
3. On the parent phone, join the displayed local Wi-Fi network by scanning the QR code or entering its credentials.
4. On the parent phone, choose **گوشی والدین — مشاهده تصویر**, enter the sender's local address, and connect.

## Limitations and safety

- This MVP streams video frames only; no audio, background camera operation, or internet relay.
- The local HTTP stream currently has no app-level authentication or encryption. Only share the Wi-Fi password with trusted people and stop streaming when finished.
- A successful cloud build does not replace testing on at least two physical Android devices.
- This app is a monitoring aid, not a replacement for direct adult supervision or safe-sleep practices.
