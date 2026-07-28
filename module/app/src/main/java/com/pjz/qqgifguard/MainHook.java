package com.pjz.qqgifguard;

import android.app.Application;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed entry for QQ GIF Guard.
 *
 * Target: QQ 9.1.25 (8368), package com.tencent.mobileqq.
 *
 * Observed animation path:
 *   RenderTask.e()
 *     -> GifInfoHandle.x(Bitmap)
 *        -> native renderFrame(long, Bitmap) in libgiflibra.so
 *
 * GifDrawable.stop() only calls saveRemainder; it does not free the native handle.
 * When the chat leaves the foreground or freeform window, scheduled render work may
 * keep running and burn CPU.
 *
 * L1 strategy:
 * - Suppress render/start while QQ is non-interactive.
 * - Always stop() when a drawable becomes invisible (covers off-screen chat items).
 * - start() again when it becomes visible and QQ is interactive (covers scroll resume).
 * - When QQ itself becomes non-interactive, stop all tracked drawables.
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG = "com.tencent.mobileqq";
    private static final String CLS_GIF_INFO = "com.tencent.libra.extension.gif.GifInfoHandle";
    private static final String CLS_GIF_DRAWABLE = "com.tencent.libra.extension.gif.GifDrawable";
    private static final String CLS_RENDER_TASK = "com.tencent.libra.extension.gif.RenderTask";

    private static final AtomicBoolean sHooked = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PKG.equals(lpparam.packageName)) {
            return;
        }
        // Install once per process.
        if (!sHooked.compareAndSet(false, true)) {
            return;
        }

        XLog.i("loading in " + lpparam.packageName
                + " process=" + lpparam.processName
                + " sdk=" + android.os.Build.VERSION.SDK_INT);

        UiVisibility.install(lpparam.classLoader);
        UiVisibility.setNonInteractiveListener(() -> GifDrawableTracker.stopAll("app-non-interactive"));
        hookApplicationCreate(lpparam.classLoader);
        hookGifInfoHandle(lpparam.classLoader);
        hookGifDrawable(lpparam.classLoader);
        hookRenderTask(lpparam.classLoader);

        XLog.i("hooks installed");
    }

    private void hookApplicationCreate(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object app = param.thisObject;
                            if (app instanceof Application) {
                                UiVisibility.onApplicationCreate((Application) app);
                            }
                        }
                    }
            );
            XLog.i("hook Application.onCreate OK");
        } catch (Throwable t) {
            XLog.e("hook Application.onCreate failed", t);
        }
    }

    private void hookGifInfoHandle(final ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_GIF_INFO, cl);

            // synchronized long x(Bitmap) -> native renderFrame
            XposedBridge.hookAllMethods(cls, "x", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!UiVisibility.isInteractive()) {
                        UiVisibility.onRenderBlocked();
                        // Frame delay sentinel: 0 prevents further scheduling.
                        param.setResult(0L);
                    } else {
                        UiVisibility.onRenderAllowed();
                    }
                }
            });
            XLog.i("hook GifInfoHandle.x OK");

            // Private native fallback.
            try {
                XposedBridge.hookAllMethods(cls, "renderFrame", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!UiVisibility.isInteractive()) {
                            UiVisibility.onRenderBlocked();
                            param.setResult(0L);
                        }
                    }
                });
                XLog.i("hook GifInfoHandle.renderFrame OK");
            } catch (Throwable t) {
                XLog.w("hook renderFrame skipped: " + t.getMessage());
            }

            try {
                XposedBridge.hookAllMethods(cls, "startDecoderThread", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!UiVisibility.isInteractive()) {
                            XLog.i("block startDecoderThread (" + UiVisibility.stats() + ")");
                            param.setResult(null);
                        }
                    }
                });
                XLog.i("hook GifInfoHandle.startDecoderThread OK");
            } catch (Throwable t) {
                XLog.w("hook startDecoderThread skipped: " + t.getMessage());
            }

            dumpMethods(cls, "GifInfoHandle");
        } catch (Throwable t) {
            XLog.e("hook GifInfoHandle failed", t);
        }
    }

    private void hookGifDrawable(final ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_GIF_DRAWABLE, cl);

            // Track constructions so background transition can stop leftovers.
            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    GifDrawableTracker.track(param.thisObject);
                }
            });

            XposedBridge.hookAllMethods(cls, "start", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    GifDrawableTracker.track(param.thisObject);
                    if (!UiVisibility.isInteractive()) {
                        XLog.i("block GifDrawable.start (" + UiVisibility.stats() + ")");
                        param.setResult(null);
                    }
                }
            });

            // Visibility policy:
            // - false: always stop. This stops off-screen chat items and prevents pool spin.
            // - true + interactive: ensure start, so scroll-back resumes animation.
            XposedBridge.hookAllMethods(cls, "setVisible", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        GifDrawableTracker.track(param.thisObject);
                        boolean visible = (Boolean) param.args[0];
                        if (!visible) {
                            XposedHelpers.callMethod(param.thisObject, "stop");
                            XLog.d("GifDrawable.setVisible(false) -> stop()");
                            return;
                        }

                        if (!UiVisibility.isInteractive()) {
                            // Becoming visible while app is background should not animate.
                            XposedHelpers.callMethod(param.thisObject, "stop");
                            return;
                        }

                        boolean running = false;
                        try {
                            Object r = XposedHelpers.callMethod(param.thisObject, "isRunning");
                            running = r instanceof Boolean && (Boolean) r;
                        } catch (Throwable ignored) {
                            // Some builds may not expose isRunning cleanly; still try start.
                        }
                        if (!running) {
                            XposedHelpers.callMethod(param.thisObject, "start");
                            XLog.d("GifDrawable.setVisible(true) -> start()");
                        }
                    } catch (Throwable t) {
                        XLog.w("setVisible after failed: " + t.getMessage());
                    }
                }
            });

            XLog.i("hook GifDrawable.start/setVisible OK");
            dumpMethods(cls, "GifDrawable");
        } catch (Throwable t) {
            XLog.e("hook GifDrawable failed", t);
        }
    }

    private void hookRenderTask(final ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_RENDER_TASK, cl);
            // RenderTask.e() performs renderFrame and reschedules itself.
            XposedBridge.hookAllMethods(cls, "e", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!UiVisibility.isInteractive()) {
                        UiVisibility.onRenderBlocked();
                        param.setResult(null);
                    }
                }
            });
            XLog.i("hook RenderTask.e OK");
        } catch (Throwable t) {
            XLog.w("hook RenderTask.e skipped: " + t.getMessage());
        }
    }

    private void dumpMethods(Class<?> cls, String label) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(label).append(" methods:");
            for (Method m : cls.getDeclaredMethods()) {
                String n = m.getName();
                if (n.contains("render") || n.contains("start") || n.contains("stop")
                        || n.equals("x") || n.equals("e") || n.equals("B") || n.equals("w")
                        || n.contains("Visible") || n.contains("recycle") || n.contains("Decoder")) {
                    sb.append(' ').append(n).append(params(m));
                }
            }
            XLog.i(sb.toString());
        } catch (Throwable ignored) {
            // Best-effort diagnostics only.
        }
    }

    private static String params(Method m) {
        Class<?>[] pts = m.getParameterTypes();
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(pts[i].getSimpleName());
        }
        sb.append(')');
        return sb.toString();
    }
}
