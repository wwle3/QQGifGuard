package com.pjz.qqgifguard;

import android.app.Application;
import android.graphics.drawable.Drawable;

import java.lang.reflect.Field;
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
 * L1+/L1.5 strategy:
 * - Suppress render/start while QQ is non-interactive.
 * - Always stop on setVisible(false).
 * - Resume on setVisible(true) only when interactive AND callback is attached.
 * - In RenderTask, skip render and stop when drawable is invisible/detached.
 * - Periodically sweep orphan running drawables (delayed CPU explosion fix).
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG = "com.tencent.mobileqq";
    private static final String CLS_GIF_INFO = "com.tencent.libra.extension.gif.GifInfoHandle";
    private static final String CLS_GIF_DRAWABLE = "com.tencent.libra.extension.gif.GifDrawable";
    private static final String CLS_RENDER_TASK = "com.tencent.libra.extension.gif.RenderTask";

    private static final AtomicBoolean sHooked = new AtomicBoolean(false);
    private static final long ORPHAN_SWEEP_INTERVAL_MS = 2000L;
    private static final AtomicBoolean sSweepStarted = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PKG.equals(lpparam.packageName)) {
            return;
        }
        if (!sHooked.compareAndSet(false, true)) {
            return;
        }

        XLog.i("loading in " + lpparam.packageName
                + " process=" + lpparam.processName
                + " sdk=" + android.os.Build.VERSION.SDK_INT);

        UiVisibility.install(lpparam.classLoader);
        UiVisibility.setNonInteractiveListener(() -> {
            GifDrawableTracker.stopAll("app-non-interactive");
        });
        hookApplicationCreate(lpparam.classLoader);
        hookGifInfoHandle(lpparam.classLoader);
        hookGifDrawable(lpparam.classLoader);
        hookRenderTask(lpparam.classLoader);
        startOrphanSweeper();

        XLog.i("hooks installed");
    }

    private void startOrphanSweeper() {
        if (!sSweepStarted.compareAndSet(false, true)) {
            return;
        }
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable sweep = new Runnable() {
            @Override
            public void run() {
                try {
                    if (!UiVisibility.isInteractive()) {
                        GifDrawableTracker.stopAll("sweep-non-interactive");
                    } else {
                        GifDrawableTracker.stopOrphans("sweep-orphans");
                    }
                } catch (Throwable t) {
                    XLog.w("orphan sweep failed: " + t.getMessage());
                } finally {
                    h.postDelayed(this, ORPHAN_SWEEP_INTERVAL_MS);
                }
            }
        };
        h.postDelayed(sweep, ORPHAN_SWEEP_INTERVAL_MS);
        XLog.i("orphan sweeper started intervalMs=" + ORPHAN_SWEEP_INTERVAL_MS);
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

            XposedBridge.hookAllMethods(cls, "x", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!UiVisibility.isInteractive()) {
                        UiVisibility.onRenderBlocked();
                        param.setResult(0L);
                    } else {
                        UiVisibility.onRenderAllowed();
                    }
                }
            });
            XLog.i("hook GifInfoHandle.x OK");

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
                        return;
                    }
                    // Refuse to start detached/invisible drawables.
                    if (!shouldAnimate(param.thisObject)) {
                        XLog.d("block GifDrawable.start (not animatable/visible)");
                        param.setResult(null);
                    }
                }
            });

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

                        if (!UiVisibility.isInteractive() || !shouldAnimate(param.thisObject)) {
                            XposedHelpers.callMethod(param.thisObject, "stop");
                            return;
                        }

                        boolean running = false;
                        try {
                            Object r = XposedHelpers.callMethod(param.thisObject, "isRunning");
                            running = r instanceof Boolean && (Boolean) r;
                        } catch (Throwable ignored) {
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
            final Field drawableField = findDrawableField(cls);

            XposedBridge.hookAllMethods(cls, "e", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!UiVisibility.isInteractive()) {
                        UiVisibility.onRenderBlocked();
                        param.setResult(null);
                        return;
                    }

                    Object drawable = null;
                    try {
                        if (drawableField != null) {
                            drawable = drawableField.get(param.thisObject);
                        } else {
                            // SafeRunnable stores GifDrawable in field "e"
                            drawable = XposedHelpers.getObjectField(param.thisObject, "e");
                        }
                    } catch (Throwable ignored) {
                    }

                    if (drawable != null) {
                        GifDrawableTracker.track(drawable);
                        if (!shouldAnimate(drawable)) {
                            try {
                                XposedHelpers.callMethod(drawable, "stop");
                            } catch (Throwable ignored) {
                            }
                            UiVisibility.onRenderBlocked();
                            param.setResult(null);
                        }
                    }
                }
            });
            XLog.i("hook RenderTask.e OK");
        } catch (Throwable t) {
            XLog.w("hook RenderTask.e skipped: " + t.getMessage());
        }
    }

    /**
     * A drawable should animate only when it still has a view callback and reports visible.
     */
    private static boolean shouldAnimate(Object drawable) {
        if (drawable == null) {
            return false;
        }
        try {
            Object visibleObj = XposedHelpers.callMethod(drawable, "isVisible");
            if (visibleObj instanceof Boolean && !(Boolean) visibleObj) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object cb = XposedHelpers.callMethod(drawable, "getCallback");
            if (cb == null) {
                return false;
            }
        } catch (Throwable ignored) {
            // If getCallback is unavailable, fall back to visible-only checks.
        }
        return true;
    }

    private static Field findDrawableField(Class<?> renderTaskCls) {
        try {
            // SafeRunnable declares final GifDrawable e
            Class<?> c = renderTaskCls;
            while (c != null && c != Object.class) {
                try {
                    Field f = c.getDeclaredField("e");
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
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
