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
 * - recycle only true orphans (no UI callback / detached) after timeout
 * - still-attached drawables are stopped but kept so returning to chat can resume
 * - if it becomes visible again before timeout, cancel recycle and allow restart
 * - handle/isRecycled checks are version-aware (9.1.25 and 9.1.60 field layouts)
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
            GuardStats.onTracked();
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
        GuardStats.onStopped(stopped);
        if (stopped > 0 || seen > 0) {
            XLog.i("stopAll reason=" + reason + " stopped=" + stopped + " tracked=" + seen);
        }
        GuardStats.maybeSummary(reason, 5_000L);
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
        GuardStats.onStopped(stopped);
        if (stopped > 0) {
            XLog.i("stopOrphans reason=" + reason + " stopped=" + stopped + " tracked=" + seen);
            GuardStats.maybeSummary(reason, 5_000L);
        }
    }

    /**
     * Recycle only orphan drawables that stayed detached longer than timeoutMs.
     * Still-attached-but-hidden GIFs are intentionally NOT recycled: destroying
     * them leaves blank ImageViews until QQ rebinds (re-enter chat / scroll).
     */
    static void recycleStale(String reason, long timeoutMs) {
        prune();
        int recycled = 0;
        int candidates = 0;
        int attachedSkipped = 0;
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
            // Attached to a View/callback: stop is enough; keep native handle for resume.
            if (isAttachedToUi(d)) {
                attachedSkipped++;
                if (e.hiddenSinceMs == 0L) {
                    e.hiddenSinceMs = now;
                }
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
        GuardStats.onRecycled(recycled);
        if (recycled > 0 || candidates > 0 || attachedSkipped > 0) {
            XLog.i("recycleStale reason=" + reason
                    + " recycled=" + recycled
                    + " candidates=" + candidates
                    + " attachedSkipped=" + attachedSkipped
                    + " timeoutMs=" + timeoutMs
                    + " tracked=" + sMap.size());
            GuardStats.maybeSummary(reason, 5_000L);
        }
    }

    /**
     * After app becomes interactive again, restart stopped-but-still-owned GIFs
     * that are visible enough to animate. Does not touch recycled orphans.
     */
    static void resumeAttached(String reason) {
        prune();
        int started = 0;
        int seen = 0;
        for (Entry e : sMap.values()) {
            Object d = e.ref.get();
            if (d == null || e.recycledByUs || isRecycled(d)) {
                continue;
            }
            seen++;
            // Only resume drawables still owned by an on-screen-ish view.
            if (!isAttachedToUi(d) || !isCallbackViewLikelyVisible(d)) {
                continue;
            }
            e.hiddenSinceMs = 0L;
            ensureDrawableVisible(d);
            if (startDrawable(d)) {
                started++;
            }
        }
        if (started > 0 || seen > 0) {
            XLog.i("resumeAttached reason=" + reason
                    + " started=" + started
                    + " tracked=" + seen);
            GuardStats.maybeSummary(reason, 5_000L);
        }
    }

    static boolean isRecycled(Object drawable) {
        if (drawable == null) {
            return true;
        }
        if (wasRecycledByUs(drawable)) {
            return true;
        }
        // Prefer explicit API when present.
        try {
            Object r = XposedHelpers.callMethod(drawable, "isRecycled");
            if (r instanceof Boolean && (Boolean) r) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        // QQ 9.1.25: l() may mean "handle freed" as boolean.
        // QQ 9.1.60: l() is an int helper and must NOT be treated as recycled flag.
        try {
            Object r = XposedHelpers.callMethod(drawable, "l");
            if (r instanceof Boolean && (Boolean) r) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        // If native handle object is already gone, treat as recycled.
        Object handle = findGifInfoHandle(drawable);
        if (handle == null) {
            // Only conclude recycled when we positively know the field map and handle is null.
            // If field discovery failed entirely, fail open (not recycled).
            if (sHandleFieldResolved) {
                return true;
            }
        } else {
            try {
                // GifInfoHandle native pointer field is commonly "a" (long). 0 => freed.
                Object nativePtr = XposedHelpers.getObjectField(handle, "a");
                if (nativePtr instanceof Long && ((Long) nativePtr) == 0L) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** True after we successfully identified a handle field name on this process. */
    private static volatile boolean sHandleFieldResolved = false;
    private static volatile String sHandleFieldName = null;

    /**
     * Resolve GifInfoHandle from GifDrawable across QQ versions.
     * 9.1.25-era builds often used field "n"; 9.1.60 uses field "o"
     * while "n" is the Bitmap. Never assume a fixed field blindly.
     */
    private static Object findGifInfoHandle(Object drawable) {
        if (drawable == null) {
            return null;
        }
        String cached = sHandleFieldName;
        if (cached != null) {
            try {
                return XposedHelpers.getObjectField(drawable, cached);
            } catch (Throwable ignored) {
                // Fall through and rediscover.
            }
        }
        // Discover by concrete type name to avoid grabbing Bitmap/Paint/etc.
        String[] candidates = new String[] {"o", "n", "m", "h", "g", "f", "e"};
        for (String name : candidates) {
            try {
                Object v = XposedHelpers.getObjectField(drawable, name);
                if (v == null) {
                    continue;
                }
                String cn = v.getClass().getName();
                if ("com.tencent.libra.extension.gif.GifInfoHandle".equals(cn)
                        || cn.endsWith(".GifInfoHandle")) {
                    sHandleFieldName = name;
                    sHandleFieldResolved = true;
                    return v;
                }
            } catch (Throwable ignored) {
            }
        }
        // As a weaker signal: any field whose class simple name is GifInfoHandle.
        try {
            for (java.lang.reflect.Field f : drawable.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(drawable);
                    if (v == null) {
                        continue;
                    }
                    if (v.getClass().getName().endsWith(".GifInfoHandle")) {
                        sHandleFieldName = f.getName();
                        sHandleFieldResolved = true;
                        return v;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * True when some UI still owns this drawable.
     * Prefer not recycling in that case so returning to the same chat can resume.
     */
    static boolean isAttachedToUi(Object drawable) {
        if (drawable == null || isRecycled(drawable)) {
            return false;
        }
        Object cb = getCallback(drawable);
        if (cb == null) {
            return false;
        }
        if (cb instanceof android.view.View) {
            try {
                return ((android.view.View) cb).isAttachedToWindow();
            } catch (Throwable ignored) {
                // Fail closed toward "attached" so we do not recycle aggressively.
                return true;
            }
        }
        // Unknown callback owner: treat as attached (safe default against blank images).
        return true;
    }

    private static Object getCallback(Object drawable) {
        try {
            return XposedHelpers.callMethod(drawable, "getCallback");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isCallbackViewLikelyVisible(Object drawable) {
        Object cb = getCallback(drawable);
        if (!(cb instanceof android.view.View)) {
            // Non-view callback: allow resume attempt if attached.
            return cb != null;
        }
        android.view.View v = (android.view.View) cb;
        try {
            if (!v.isAttachedToWindow()) {
                return false;
            }
            if (v.getVisibility() != android.view.View.VISIBLE) {
                return false;
            }
            // getGlobalVisibleRect is a practical "on screen" hint without requiring full draw.
            android.graphics.Rect r = new android.graphics.Rect();
            return v.getGlobalVisibleRect(r);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void ensureDrawableVisible(Object drawable) {
        try {
            Object visibleObj = XposedHelpers.callMethod(drawable, "isVisible");
            if (visibleObj instanceof Boolean && (Boolean) visibleObj) {
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            // Best-effort: mirror View visibility so GifDrawable can animate again.
            XposedHelpers.callMethod(drawable, "setVisible", true, false);
        } catch (Throwable ignored) {
        }
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
        return isAttachedToUi(drawable);
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

    private static boolean startDrawable(Object d) {
        try {
            boolean running = false;
            try {
                Object r = XposedHelpers.callMethod(d, "isRunning");
                if (r instanceof Boolean) {
                    running = (Boolean) r;
                }
            } catch (Throwable ignored) {
            }
            if (!running) {
                XposedHelpers.callMethod(d, "start");
                return true;
            }
        } catch (Throwable t) {
            XLog.w("startDrawable failed: " + t.getMessage());
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
            // Fallback: free native handle via version-aware field discovery.
            // 9.1.60: handle is field "o"; "n" is Bitmap and must not be used.
            try {
                Object handle = findGifInfoHandle(d);
                if (handle != null) {
                    try {
                        XposedHelpers.callMethod(handle, "w");
                        return true;
                    } catch (Throwable ignored) {
                    }
                    try {
                        // Some builds expose free(long) / free native path.
                        XposedHelpers.callMethod(handle, "free",
                                XposedHelpers.getLongField(handle, "a"));
                        return true;
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable t2) {
                XLog.w("free-handle fallback failed: " + t2.getMessage());
            }
            return false;
        }
    }
}
