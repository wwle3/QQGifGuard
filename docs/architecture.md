# Architecture

## Goals

- Prevent recurrence of background/freeform GIF CPU spin
- Keep foreground GIF playback working
- Prefer Java-layer LSPosed hooks over native binary patching

## Non-goals

- Killing QQ processes as a feature
- Relying on OEM cgroup freeze as a fix
- Shipping Frida scripts as the long-term solution

## Runtime model

```text
QQ process load
  -> MainHook.handleLoadPackage
      -> UiVisibility lifecycle tracking
      -> hook RenderTask / GifInfoHandle / GifDrawable

Foreground / visible
  -> allow renderFrame path

Background / freeform not interactive
  -> suppress render/start
  -> stop invisible drawables
```

## Key classes

| Class | Role |
|---|---|
| `MainHook` | LSPosed entry, installs hooks once per process |
| `UiVisibility` | Interactive-state estimator |
| `XLog` | Stable `QQGifGuard` log tag |

## Hook surface (9.1.25)

| Target | Method | Behavior when not interactive |
|---|---|---|
| `RenderTask` | `e()` | skip body |
| `GifInfoHandle` | `x(Bitmap)` | return `0L` |
| `GifInfoHandle` | `renderFrame(long,Bitmap)` | return `0L` |
| `GifInfoHandle` | `startDecoderThread(long)` | no-op |
| `GifDrawable` | `start()` | no-op |
| `GifDrawable` | `setVisible(false, ...)` | force `stop()` |

## Interactive policy

A process is considered interactive when:

1. `startedActivityCount > 0`, or
2. `RunningAppProcessInfo.importance <= IMPORTANCE_VISIBLE`

Otherwise GIF animation paths are suppressed.

## Failure modes

| Mode | Mitigation |
|---|---|
| Method renamed by hotfix | log method dump; fail soft |
| Lifecycle callbacks missing | fail-open until context ready |
| Already leaked old process | require cold start |
| Freezer hides residual work | validate after UID unfreeze |

## Roadmap

### L1 (implemented)

Background/freeform render suppression + visibility stop.

### L2 (planned)

On chat item detach / page destroy:

- cancel scheduled futures
- call `GifDrawable.recycle()` / native free path
- reduce retained `chatraw` FDs

### L3 (optional)

Scope hooks to main UI process only; add allowlist for sticker panel if needed.
