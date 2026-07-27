# QQ GIF Guard

LSPosed module that prevents QQ chat GIFs from continuously decoding in the background or freeform window, which otherwise leads to sustained high CPU usage.

## Problem

On QQ `9.1.25 (8368)`, chat GIFs are rendered through Tencent Libra:

```text
RenderTask.e()
  -> GifInfoHandle.x(Bitmap)
     -> native renderFrame(long, Bitmap)   # libgiflibra.so
```

`GifDrawable.stop()` only saves remainder state. It does **not** free the native handle. After leaving a chat session or keeping QQ in freeform/background, scheduled render work can keep running.

OEM freezers may hide the symptom by freezing the whole UID. Once unfrozen, the same process often resumes GIF spin without returning to the foreground.

## Solution (L1)

When QQ is not interactively visible:

1. Suppress `RenderTask.e`
2. Suppress `GifInfoHandle.x` / `renderFrame` (return `0L`)
3. Suppress `GifInfoHandle.startDecoderThread`
4. Suppress `GifDrawable.start`
5. On `GifDrawable.setVisible(false)`, force `stop()`

When QQ is foreground/visible, rendering is allowed.

## Project layout

```text
module/     # Android / LSPosed module sources
scripts/    # Windows / Linux build helpers
docs/       # Architecture and verification
dist/       # Build output (gitignored)
```

## Requirements

- JDK 17+ (JDK 21 recommended)
- Android SDK with `platforms;android-34` and `build-tools;34.x`
- LSPosed (Zygisk) on the target device
- Target app: `com.tencent.mobileqq` 9.1.25 (8368)

## Build

Requirements:

- JDK 17+ (21 recommended)
- Android SDK (platforms;android-34, build-tools;34.x)
- ANDROID_HOME or ANDROID_SDK_ROOT
- JAVA_HOME recommended

### Windows

```bat
scripts\build.bat
scripts\build.bat release
```

### Linux / macOS

```bash
chmod +x scripts/build.sh
./scripts/build.sh
./scripts/build.sh release
```

### Manual Gradle

```bash
cd module
# optional: create local.properties with sdk.dir=<Android SDK path>
./gradlew :app:assembleDebug
```

Output:

```text
dist/QQGifGuard-debug.apk
# or
module/app/build/outputs/apk/debug/app-debug.apk
```

## Install

```bash
adb install -r dist/QQGifGuard-debug.apk
```

In LSPosed Manager:

1. Enable **QQ GIF Guard**
2. Scope only **QQ** (`com.tencent.mobileqq`)
3. Force-stop QQ, then cold start it

> Installing the module does not repair an already leaking process. Always validate after a cold start.

## Verify

```bash
adb logcat -s QQGifGuard:D LSPosed-Bridge:I
```

Expected:

- Module loads in `com.tencent.mobileqq`
- Foreground chat GIFs still play (`render allowed`)
- Leaving chat / freeform triggers `setVisible(false) -> stop()` and/or `render blocked`
- After UID unfreeze, no sustained `pool-38` / `libgiflibra` CPU spin

## Version lock

Validated against:

| Item | Value |
|---|---|
| Package | `com.tencent.mobileqq` |
| versionName | `9.1.25` |
| versionCode | `8368` |
| Native lib | `libgiflibra.so` |
| Java package | `com.tencent.libra.extension.gif` |

## Limitations

- L1 is a safety net, not full resource reclamation
- L2 should recycle/free on item detach / page destroy
- Sticker panel / preview false positives need policy tuning if observed
- Hooking QQ has residual detection surface; use at your own risk
