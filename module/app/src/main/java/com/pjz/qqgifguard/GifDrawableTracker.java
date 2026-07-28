package com.pjz.qqgifguard;

import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XposedHelpers;

/**
 * Tracks live GifDrawable instances so we can stop orphans that keep animating
 * after leaving the viewport or losing their view callback.
 */
final class GifDrawableTracker {
    private static final CopyOnWriteArrayList<WeakReference<Object>> sDrawables =
            new CopyOnWriteArrayList<>();

    private GifDrawableTracker() {
    }

    static void track(Object drawable) {
        if (drawable == null) {
            return;
        }
        for (WeakReference<Object> ref : sDrawables) {
            if (ref.get() == drawable) {
                return;
            }
        }
        sDrawables.add(new WeakReference<>(drawable));
        prune();
    }

    static void prune() {
        for (WeakReference<Object> ref : sDrawables) {
            if (ref.get() == null) {
                sDrawables.remove(ref);
            }
        }
    }

    static int size() {
        prune();
        int n = 0;
        for (WeakReference<Object> ref : sDrawables) {
            if (ref.get() != null) {
                n++;
            }
        }
        return n;
    }

    /** Stop every tracked drawable that is running. */
    static void stopAll(String reason) {
        stopMatching(reason, /*onlyInvisibleOrDetached*/ false);
    }

    /**
     * Stop drawables that should not animate right now:
     * - app non-interactive: all
     * - otherwise: not visible, or no callback (detached from view tree)
     */
    static void stopOrphans(String reason) {
        stopMatching(reason, /*onlyInvisibleOrDetached*/ true);
    }

    private static void stopMatching(String reason, boolean onlyInvisibleOrDetached) {
        prune();
        int stopped = 0;
        int seen = 0;
        for (WeakReference<Object> ref : sDrawables) {
            Object d = ref.get();
            if (d == null) {
                continue;
            }
            seen++;
            try {
                if (onlyInvisibleOrDetached) {
                    boolean visible = true;
                    try {
                        Object v = XposedHelpers.callMethod(d, "isVisible");
                        visible = !(v instanceof Boolean) || (Boolean) v;
                    } catch (Throwable ignored) {
                    }
                    Object cb = null;
                    try {
                        cb = XposedHelpers.callMethod(d, "getCallback");
                    } catch (Throwable ignored) {
                    }
                    if (visible && cb != null) {
                        continue;
                    }
                }

                boolean running = true;
                try {
                    Object r = XposedHelpers.callMethod(d, "isRunning");
                    if (r instanceof Boolean) {
                        running = (Boolean) r;
                    }
                } catch (Throwable ignored) {
                }
                if (running) {
                    XposedHelpers.callMethod(d, "stop");
                    stopped++;
                }
            } catch (Throwable t) {
                XLog.w("stopMatching failed: " + t.getMessage());
            }
        }
        if (stopped > 0) {
            XLog.i("stopMatching reason=" + reason
                    + " stopped=" + stopped
                    + " tracked=" + seen
                    + " orphansOnly=" + onlyInvisibleOrDetached);
        }
    }
}
