package com.pjz.qqgifguard;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XposedHelpers;

/**
 * Tracks live GifDrawable instances so we can stop them when QQ becomes
 * non-interactive, even if individual setVisible(false) calls were missed.
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
        // Avoid duplicates for the same instance.
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

    static void stopAll(String reason) {
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
                boolean running = false;
                try {
                    Object r = XposedHelpers.callMethod(d, "isRunning");
                    running = r instanceof Boolean && (Boolean) r;
                } catch (Throwable ignored) {
                    running = true; // best-effort stop
                }
                if (running) {
                    XposedHelpers.callMethod(d, "stop");
                    stopped++;
                }
            } catch (Throwable t) {
                XLog.w("stopAll failed: " + t.getMessage());
            }
        }
        if (stopped > 0 || seen > 0) {
            XLog.i("stopAll reason=" + reason + " stopped=" + stopped + " tracked=" + seen);
        }
    }
}
