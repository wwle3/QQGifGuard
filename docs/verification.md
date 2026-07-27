# Verification checklist

## Functional

- [ ] Foreground chat GIF animates normally
- [ ] Leaving chat stops continuous animation work
- [ ] Freeform/small-window with GIF chat does not sustain high CPU
- [ ] Returning to foreground can play GIF again

## Log signals

adb logcat -s QQGifGuard:D LSPosed-Bridge:I

Look for:

- hooks installed
- render allowed while chatting
- setVisible(false) -> stop() and/or render blocked after leave

## Root metrics

PID=$(pidof com.tencent.mobileqq)
ls -l /proc/$PID/fd | grep -c chatraw

# unfreeze QQ UID then re-sample jiffies
echo 0 > /sys/fs/cgroup/apps/uid_<qq_uid>/cgroup.freeze

## Pass criteria

- No sustained libgiflibra / GIF render loop after leave/unfreeze
- No stable crash loop introduced by hooks
- Foreground playback remains acceptable
