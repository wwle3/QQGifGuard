package com.pjz.qqgifguard;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * Tracks GifDrawable instances for stop + delayed recycle.
 *
 * Policy:
 * - stop immediately when hidden/detached/non-interactive
 * - recycle only after staying unusable for a timeout (recoverable L2)
 * - if it becomes visible again before timeout, cancel recycle and allow restart
 */
final class GifDrawableTracker {
    private static final class Entry {
        final WeakReference<Object> ref;
        volatile long hiddenSinceMs;
        volatile boolean recycledByUs;

        Entry(Object drawable) {
            this.ref = new WeakReference<>(drawable);
            this.hiddenSinceMs = 0L;
            this.recycledByUs = false;
        }
    }

    // IdentityHash-like tracking via System.identityHashCode key is unsafe on collision;
    // use weak key through ConcurrentHashMap with identity wrapper.
    private static final class Id {
        final int hash;
        final WeakReference<Object> ref;

        Id(Object o) {
            this.hash = System.identityHashCode(o);
            this.ref = new WeakReference<>(o);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Id)) {
                return false;
            }
            Object a = ref.get();
            Object b = ((Id) other).ref.get();
            return a != null && a == b;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final ConcurrentHashMap<Id, Entry> sMap = new ConcurrentHashMap<>();

    private GifDrawableTracker() {
    }

    static void track(Object drawable) {
        if (drawable == null) {
            return;
        }
        Id id = new Id(drawable);
        Entry existing = sMap.get(id);
        if (existing == null) {
            sMap.put(id, new Entry(drawable));
        }
        prune();
    }

    static void markHidden(Object drawable) {
        if (drawable == null) {
            return;
        }
        track(drawable);
        Entry e = sMap.get(new Id(drawable));
        if (e != null && e.hiddenSinceMs == 0L) {
            e.hiddenSinceMs = System.currentTimeMillis();
        }
    }

    static void markVisible(Object drawable) {
        if (drawable == null) {
            return;
        }
        track(drawable);
        Entry e = sMap.get(new Id(drawable));
        if (e != null) {
            e.hiddenSinceMs = 0L;
        }
    }

    static boolean wasRecycledByUs(Object drawable) {
        if (drawable == null) {
            return false;
        }
        Entry e = sMap.get(new Id(drawable));
        return e != null && e.recycledByUs;
    }

    static void prune() {
        Iterator<Map.Entry<Id, Entry>> it = sMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Id, Entry> en = it.next();
            if (en.getKey().ref.get() == null || en.getValue().ref.get() == null) {
                it.remove();
            }
        }
    }

    static int size() {
        prune();
        return sMap.size();
    }

    static void stopAll(String reason) {
        prune();
        int stopped = 0;
        int seen = 0;
        long now = System.currentTimeMillis();
        for (Entry e : sMap.values()) {
            Object d = e.ref.get();
            if (d == null || e.recycledByUs) {
                continue;
            }
            seen++;
            if (e.hiddenSinceMs == 0L) {
                e.hiddenSinceMs = now;
            }
            if (stopDrawable(d)) {
                stopped++;
            }
        }
        if (stopped > 0 || seen > 0) {
            XLog.i("stopAll reason=" + reason + " stopped=" + stopped + " tracked=" + seen);
        }
    }

    static void stopOrphans(String reason) {
        prune();
        int stopped = 0;
        int seen = 0;
        long now = System.currentTimeMillis();
        for (Entry e : sMap.values()) {
            Object d = e.ref.get();
            if (d == null || e.recycledByUs) {
                continue;
            }
            seen++;
            if (shouldKeepAnimating(d)) {
                e.hiddenSinceMs = 0L;
                continue;
            }
            if (e.hiddenSinceMs == 0L) {
                e.hiddenSinceMs = now;
            }
            if (stopDrawable(d)) {
                stopped++;
            }
        }
        if (stopped > 0) {
            XLog.i("stopOrphans reason=" + reason + " stopped=" + stopped + " tracked=" + seen);
        }
    }

    /**
     * Recycle drawables that remained hidden/detached longer than timeoutMs.
     * This is the limited L2 path: destroy only stale ones.
     */
    static void recycleStale(String reason, long timeoutMs) {
        prune();
        int recycled = 0;
        int candidates = 0;
        long now = System.currentTimeMillis();
        for (Entry e : sMap.values()) {
            Object d = e.ref.get();
            if (d == null || e.recycledByUs) {
                continue;
            }
            if (shouldKeepAnimating(d)) {
                e.hiddenSinceMs = 0L;
                continue;
            }
            if (e.hiddenSinceMs == 0L) {
                e.hiddenSinceMs = now;
                continue;
            }
            if (now - e.hiddenSinceMs < timeoutMs) {
                continue;
            }
            candidates++;
            if (recycleDrawable(d)) {
                e.recycledByUs = true;
                recycled++;
            }
        }
        if (recycled > 0 || candidates > 0) {
            XLog.i("recycleStale reason=" + reason
                    + " recycled=" + recycled
                    + " candidates=" + candidates
                    + " timeoutMs=" + timeoutMs
                    + " tracked=" + sMap.size());
        }
    }

    static boolean isRecycled(Object drawable) {
        if (drawable == null) {
            return true;
        }
        if (wasRecycledByUs(drawable)) {
            return true;
        }
        try {
            // GifDrawable.l() => handle already freed
            Object r = XposedHelpers.callMethod(drawable, "l");
            if (r instanceof Boolean && (Boolean) r) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object r = XposedHelpers.callMethod(drawable, "isRecycled");
            if (r instanceof Boolean && (Boolean) r) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean shouldKeepAnimating(Object drawable) {
        if (drawable == null || isRecycled(drawable)) {
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
        }
        return true;
    }

    private static boolean stopDrawable(Object d) {
        try {
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
                return true;
            }
        } catch (Throwable t) {
            XLog.w("stopDrawable failed: " + t.getMessage());
        }
        return false;
    }

    private static boolean recycleDrawable(Object d) {
        try {
            // Ensure stopped first.
            try {
                XposedHelpers.callMethod(d, "stop");
            } catch (Throwable ignored) {
            }
            XposedHelpers.callMethod(d, "recycle");
            return true;
        } catch (Throwable t) {
            XLog.w("recycleDrawable failed: " + t.getMessage());
            // Fallback: try native free via handle field "n" -> w()
            try {
                Object handle = XposedHelpers.getObjectField(d, "n");
                if (handle != null) {
                    XposedHelpers.callMethod(handle, "w");
                    return true;
                }
            } catch (Throwable t2) {
                XLog.w("free-handle fallback failed: " + t2.getMessage());
            }
            return false;
        }
    }
}
