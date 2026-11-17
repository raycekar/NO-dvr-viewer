# NO-dvr-viewer

A small Android app that shows a live grid of all the cameras on a Xiongmai/Sofia-based DVR
on your local network.

The app is plain Java on the Android framework: no Gradle, no AndroidX, no Kotlin, and no
third-party libraries. The APK is about 30 KB.

## Why

Many low-cost DVRs advertise RTSP and ONVIF, but on some models these are broken stubs.
The vendor apps are often slow, cloud-dependent, or full of ads. These DVRs do serve a
working HTTP snapshot endpoint, which returns one JPEG for one channel per request.

This app polls that endpoint for each channel, about once a second, and shows the frames
in a grid. The result is a near-live view of all cameras that needs no cloud account and
uses only your LAN.

## Supported hardware

**Tested:** NightOwl **DVR-DP8-12** (8 channels), with an Android 14 phone.

**Likely to work:** other DVRs and NVRs that run Xiongmai "Sofia" firmware and serve
`/cgi-bin/guest/Video.cgi`. Use the [curl check](#check-your-dvr-first) to find out.

> **What is Xiongmai?** Hangzhou Xiongmai Technology is a Chinese maker of DVR/NVR boards
> and firmware. Its hardware is sold under many brand names. The firmware's main process is
> called `Sofia`, and the devices usually also work with the XMEye app. If your DVR's web
> page or app mentions XMEye, Sofia, or "XM", it is probably a Xiongmai device.

The app needs Android 7.0 (API 24) or later.

## Features

- **Layout follows orientation.** Portrait shows a full-width scrolling list; landscape
  shows a grid. The layout changes live when you turn the phone.
- **1–16 feeds**, default 4.
- **Zoom on every tile.** Pinch, double-tap, and drag to pan. In the portrait list, a tile
  takes touch input only while it is zoomed, so the list still scrolls.
- **Fullscreen view.** Tap a tile to open one channel. Swipe left/right to change channel
  (it wraps around). Pinch to zoom. Tap or press Back to return to the grid. Only the
  channel on screen is polled.
- **Stall indicator.** Each feed shows a green `HH:MM:SS` timestamp that changes only when a
  new frame arrives. A frozen timestamp means that feed has stalled.
- **Refresh interval** of 1–5 seconds.
- **Option to crop tiles** to fill the grid cell, or show the full frame.
- **Battery-friendly.** Polling stops when the app goes to the background and starts again
  when you return. The screen stays on while the app is open.

## Check your DVR first

For each channel `N` (1-based), the app sends:

```
GET http://<dvr-ip>/cgi-bin/guest/Video.cgi?channel=N
Authorization: Basic <base64(user:password)>
```

Before you build the app, make sure your DVR answers:

```bash
curl -u '<user>:<password>' -o ch1.jpg 'http://<dvr-ip>/cgi-bin/guest/Video.cgi?channel=1'
file ch1.jpg        # expect: JPEG image data
```

If you get a JPEG, the app will work. If you get `401`, check the credentials. If you get
`404` or HTML, your firmware does not have this endpoint.

## First-run setup in the app

1. Open the app. The settings dialog opens on first start. Later, open it with the gear
   icon at the top right.
2. Enter the DVR address: `<dvr-ip>`, or `<dvr-ip>:<port>` if the DVR web server is not
   on port 80.
3. Enter the DVR username and password. Use a view-only account (see [Security](#security)).
4. Set the number of feeds and the refresh interval.
5. Tap **Save**.

## Build

### Requirements

- JDK 17
- Android SDK command-line tools, with these packages:
  ```bash
  sdkmanager "build-tools;34.0.0" "platforms;android-34" "platform-tools"
  ```
- `bash` and `python3`

No Gradle is needed. `build.sh` calls the SDK tools directly:
`aapt2` compile/link → `javac` → `d8` → add `classes.dex` → `zipalign` → `apksigner`.

### 1. Make a signing keystore (one time)

The repository has no keystore. Make your own and keep it out of git (`.gitignore`
already ignores `*.keystore` and `*.jks`):

```bash
keytool -genkeypair -keystore ~/dvrviewer.keystore \
  -alias androiddebugkey -storepass android -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

Keep this file. Android installs an update only if it is signed with the same key. If you
lose the key, you must uninstall the app (and lose its settings) before you can install a
build signed with a new key. For a key that you keep for a long time, use a strong password
and pass it with `KS_PASS` / `KEY_PASS`.

### 2. Choose an application ID

The application ID is the app's unique name on the phone. Choose one at build time with
`APP_ID`, for example `io.github.<your-user>.dvrviewer`. The rules: segments of letters,
digits, and `_`, each segment starts with a letter, and at least one dot.

If `APP_ID` is not set and you run the build in a terminal, `build.sh` asks for it. In a
script or CI, it stops with an error.

Use the same `APP_ID` (and the same keystore) every time, so new builds install as updates.
A different `APP_ID` installs as a separate app. The Java source package
(`dvrviewer.app`) does not change; `aapt2 --rename-manifest-package` sets the ID.

### 3. Build the APK

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
export KEYSTORE=~/dvrviewer.keystore
export APP_ID=io.github.<your-user>.dvrviewer
bash build.sh                     # -> DvrCam.apk
```

Optional variables:

| Variable      | Default            | Meaning                       |
|---------------|--------------------|-------------------------------|
| `KEY_ALIAS`   | `androiddebugkey`  | key alias in the keystore     |
| `KS_PASS`     | `android`          | keystore password             |
| `KEY_PASS`    | value of `KS_PASS` | key password                  |
| `BUILD_TOOLS` | `34.0.0`           | build-tools version           |
| `OUT_DIR`     | `./out`            | intermediate files            |
| `APK_OUT`     | `./DvrCam.apk`     | output APK                    |

`env.sh` checks `JAVA_HOME` and `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) and puts the tools on
`PATH`. You can `source env.sh` in your shell to use `adb` from the SDK.

## Install with ADB

Enable Developer options and USB or wireless debugging on the phone, then:

```bash
adb install -r DvrCam.apk         # -r keeps the saved settings
```

For wireless debugging, the pairing port and the connect port are different.
`adb mdns services` shows both:

```bash
adb pair <phone-ip>:<pair-port> <pairing-code>
adb connect <phone-ip>:<connect-port>
adb -s <phone-ip>:<connect-port> install -r DvrCam.apk
```

You can also copy the APK to the phone and open it, if you allow installs from that source.

## Limits and known issues

- **Not real video.** The frame rate is about 1 frame per second at best, set by the refresh
  interval and the DVR's speed. There is no audio.
- **Live view only.** There is no playback, recording, PTZ control, or motion alerts.
- **Plain HTTP.** These DVRs have no HTTPS, so frames and the Basic-auth credentials cross
  the network unencrypted.
- **Manual channel count.** The app does not detect how many channels the DVR has.
- **No discovery.** You must enter the DVR's IP address. Give the DVR a fixed IP (a DHCP
  reservation) so the address does not change.
- **Credentials on the phone.** The app keeps them in its private app storage
  (SharedPreferences). That storage is not encrypted beyond Android's own device encryption.
- **RTSP/ONVIF are not used**, because on the tested model they do not work.
- **Launcher icon cache.** If an updated icon does not show, restart the phone.

## Security

Xiongmai-based devices have a long history of security problems, including default
passwords and devices used in large botnets (for example, Mirai in 2016). Treat the DVR as
untrusted.

- **Use a guest or view-only DVR account** for this app, if your DVR supports it. Do not use
  the admin account. Test the account with the curl check first.
- **Keep the DVR on your LAN only.** Do not forward its ports to the internet, and turn off
  UPnP and the vendor cloud/P2P feature if you do not need them.
- **For remote access, use a VPN** (for example WireGuard or Tailscale) to reach your LAN.
  Do not expose the DVR's web port.
- Change the DVR's default passwords, and update its firmware if updates are available.

The app sends the credentials only to the address that you enter. It has no analytics and
contacts no other server.

## Code layout

- `src/dvrviewer/app/MainActivity.java` — layouts, polling, settings dialog.
- `src/dvrviewer/app/TouchImageView.java` — pinch/pan/double-tap zoom view.
- `res/` — vector launcher icons (no PNG files).
- `docs/architecture.md` — more detail on polling, layouts, touch, and the icon.
- Do not use Java lambdas: the build has no desugaring step, and `android.jar` has no
  `LambdaMetafactory`. Use anonymous inner classes.

## Not affiliated

This project is not affiliated with, endorsed by, or supported by NightOwl, Xiongmai, or any
DVR maker. Product names are used only to identify compatible hardware.

## License

MIT. See [LICENSE](LICENSE).
