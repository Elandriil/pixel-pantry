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
```

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

## Sideload to the Sony Bravia (Google TV)

1. On the TV: Settings → Device Preferences → About → tap **Build** 7 times to enable developer mode.
2. Settings → Device Preferences → Developer options → turn on **Network debugging** (USB debugging too if offered).
3. From the Mac (same network), using the TV's IP address:

   ```sh
   adb connect <tv-ip>:5555        # accept the prompt on the TV the first time
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. Pixel Pantry appears in the TV's app row (leanback banner).
