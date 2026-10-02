# AgentPet Android companion

This native Android app is the Messenger-style floating companion for the
Cloudflare relay. It runs a visible foreground service, requests the system
overlay grant, and keeps a draggable pop-up above other apps while connected.

## Run

Open `android/` in Android Studio (JDK 17; Android SDK 35). Enter the deployed
relay URL and a `companion` device token, grant **Display over other apps**, and
tap **Allow overlay and start pet**.

The companion reads `snapshot` and `event` WebSocket frames and renders idle,
working, waiting, or done. The present shell uses a compact pixel-style paw
until the shared pet-pack renderer is added; relay profile frames already carry
the shared settings needed for that next rendering layer.

The app deliberately shows a persistent notification: Android requires one for
the foreground service that keeps an overlay alive. Its **Clear cloud activity
history** control calls `DELETE /v1/logs` after confirmation; it never clears
the selected pet, settings, or device token.
