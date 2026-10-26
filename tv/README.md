# Pixel Pantry — Android TV app

Kotlin + Compose for TV client for the Pixel Pantry server (`../server`).
Package / application id: `app.pixelpantry.tv`.

## Environment

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

`local.properties` (not committed) must contain `sdk.dir=/Users/<you>/Library/Android/sdk`.

SDK packages: `platform-tools`, `platforms;android-34`, `platforms;android-35`
(compileSdk 35 is required by Media3 1.5.1; targetSdk stays 34), `build-tools;34.0.0`,
`emulator`, `system-images;android-34;android-tv;arm64-v8a`.

## Build and test

```sh
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk (sideload build, see below)
```

The release build (`versionName` 0.1.0, `versionCode` 1) is **not minified and is signed with the debug key**
(`signingConfig = signingConfigs.getByName("debug")` in `app/build.gradle.kts`), so it installs without a release
keystore. That is fine for sideloading onto your own TV; it is not a Play Store build. Because both builds share
the application id and the debug key, `adb install -r` of one over the other keeps the app's data (server
address, cached settings).

## Emulator

One-time AVD creation (run the avdmanager that lives inside the SDK so it finds the system image):

```sh
$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd -n pantry_tv \
  -k "system-images;android-34;android-tv;arm64-v8a" -d tv_1080p --force
```

Start, install, launch, screenshot:

```sh
$ANDROID_HOME/emulator/emulator -avd pantry_tv -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.pixelpantry.tv/.MainActivity   # or: adb shell monkey -p app.pixelpantry.tv 1
adb exec-out screencap -p > shot.png
```

## Sideloading on the Bravia (Google TV)

1. On the TV: Settings → System → About → tap **Android TV OS build** 7 times to enable developer mode
   (older menus: Settings → Device Preferences → About → **Build**).
2. Settings → System → Developer options → turn on **USB debugging** and, where offered, **Network debugging**
   (ADB over the network, port 5555).
3. Build and install from the Mac (same network), using the TV's IP address:

   ```sh
   ./gradlew :app:assembleRelease
   adb connect <tv-ip>:5555        # accept the "Allow USB debugging?" prompt on the TV the first time
   adb -s <tv-ip>:5555 install -r app/build/outputs/apk/release/app-release.apk
   adb -s <tv-ip>:5555 shell am start -n app.pixelpantry.tv/.MainActivity
   ```

   These are the same commands the emulator walkthrough uses (`adb install -r …/app-release.apk`, `am start`), with
   `-s <tv-ip>:5555` added because the emulator may be attached too.
4. Pixel Pantry appears in the TV's app row (leanback banner). On first start it finds the server by mDNS, or asks
   for `host:port`; after that it starts straight on Home with the stored address and checks it in the background
   (a toast says so if the server does not answer). A page that already shows content keeps it when a refresh
   fails and only toasts "Could not refresh"; a page (or the player) that has nothing loaded yet shows "Cannot
   reach the server" with Retry / Change server.

### Real-TV checklist (verify on the Bravia, in this order)

1. Long-press OK on an episode card: the menu opens and nothing runs on release; the episode's progress is unchanged afterwards.
2. Remote keys reach the app: CH+ / CH− switch episodes (Sony firmware may route them to the tuner); ⏮ ⏭, ⏪ ⏩, ■, and separate ▶ / ‖ versus ⏯ all do what the player overlay says.
3. Audio: a remux whose default track is DTS, plus DTS-only, TrueHD, EAC3 and AC3 files, on the TV speakers and over ARC/eARC. Expect no error screen and no silent video.
4. 4K HEVC and high-bitrate remux direct play: start, seek, hold LEFT/RIGHT (repeat rate and skip accumulation), no out-of-memory crash, no stutter.
5. Press HOME mid-playback, open YouTube or Netflix and play there, come back: the other app plays, Pixel Pantry resumes with OK, no "unsupported format" error. Also: screen saver while paused, and TV standby via HDMI-CEC.
6. Process death: after using other heavy apps, relaunch from the launcher; the restored screen loads with the right server address.
7. MKV embedded subtitles: default and forced flags, the choice restored on the next play, the correct track when there are several English tracks; sidecar `.srt` (including cp1252), sub delay, OpenSubtitles download and switching while playing.
8. Server going away mid-playback (PC sleep): the error with the file name appears within about 10–15 s; Back works.
9. mDNS discovery on the home network; a DHCP address change; Retry recovers.
10. OK to pause, then RIGHT, then OK: RIGHT skips and OK resumes (it must not switch episodes).
11. Rescan from the sidebar, then the auto-refresh toast; posters appear once the TMDB key is set on the server.
