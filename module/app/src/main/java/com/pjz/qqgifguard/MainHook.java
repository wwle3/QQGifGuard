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
 * - If QQ is not interactively visible, suppress render/start paths.
 * - Force-stop drawables only when the app itself is non-interactive.
 * - When a drawable becomes visible again while interactive, ensure start() is called
 *   so in-chat scroll recycle can resume animation.
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

            XposedBridge.hookAllMethods(cls, "start", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!UiVisibility.isInteractive()) {
                        XLog.i("block GifDrawable.start (" + UiVisibility.stats() + ")");
                        param.setResult(null);
                    }
                }
            });

            // Important:
            // Do NOT unconditionally stop() on every setVisible(false).
            // In-chat scrolling detaches items and flips visibility; forcing stop there
            // can leave GIFs frozen until the chat page is re-entered.
            // Only force-stop when the app itself is non-interactive (background/freeform).
            // When becoming visible again while interactive, ensure start() resumes.
            XposedBridge.hookAllMethods(cls, "setVisible", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        boolean visible = (Boolean) param.args[0];
                        if (!visible) {
                            if (!UiVisibility.isInteractive()) {
                                XposedHelpers.callMethod(param.thisObject, "stop");
                                XLog.d("GifDrawable.setVisible(false) -> stop() [non-interactive]");
                            }
                            return;
                        }

                        if (!UiVisibility.isInteractive()) {
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
