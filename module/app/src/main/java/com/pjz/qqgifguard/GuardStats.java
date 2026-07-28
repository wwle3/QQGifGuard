package com.pjz.qqgifguard;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local counters so a short log window can answer:
 * did we fail to stop, recycle too aggressively, or only block renders?
 */
final class GuardStats {
    private static final AtomicLong sTrackedCreates = new AtomicLong(0);
    private static final AtomicLong sStopActions = new AtomicLong(0);
    private static final AtomicLong sRecycleActions = new AtomicLong(0);
    private static final AtomicLong sStartBlocked = new AtomicLong(0);
    private static final AtomicLong sRenderBlocked = new AtomicLong(0);
    private static final AtomicLong sRenderAllowed = new AtomicLong(0);
    private static final AtomicLong sLastSummaryMs = new AtomicLong(0);

    private GuardStats() {
    }

    static void onTracked() {
        sTrackedCreates.incrementAndGet();
    }

    static void onStopped(int n) {
        if (n > 0) {
            sStopActions.addAndGet(n);
        }
    }

    static void onRecycled(int n) {
        if (n > 0) {
            sRecycleActions.addAndGet(n);
        }
    }

    static void onStartBlocked() {
        sStartBlocked.incrementAndGet();
    }

    static void onRenderBlocked() {
        sRenderBlocked.incrementAndGet();
    }

    static void onRenderAllowed() {
        sRenderAllowed.incrementAndGet();
    }

    static long renderAllowed() {
        return sRenderAllowed.get();
    }

    static long renderBlocked() {
        return sRenderBlocked.get();
    }

    static String snapshot() {
        return "trackedCreates=" + sTrackedCreates.get()
                + " liveTracked=" + GifDrawableTracker.size()
                + " stopped=" + sStopActions.get()
                + " recycled=" + sRecycleActions.get()
                + " startBlocked=" + sStartBlocked.get()
                + " renderAllowed=" + sRenderAllowed.get()
                + " renderBlocked=" + sRenderBlocked.get()
                + " " + UiVisibility.stats();
    }

    /**
     * Emit a compact summary at most once per minIntervalMs.
     * Useful after leave-chat / idle to diagnose within ~30s.
     */
    static void maybeSummary(String reason, long minIntervalMs) {
        long now = System.currentTimeMillis();
        long last = sLastSummaryMs.get();
        if (now - last < minIntervalMs) {
            return;
        }
        if (!sLastSummaryMs.compareAndSet(last, now)) {
            return;
        }
        XLog.i("stats reason=" + reason + " " + snapshot());
    }

    static void forceSummary(String reason) {
        sLastSummaryMs.set(System.currentTimeMillis());
        XLog.i("stats reason=" + reason + " " + snapshot());
    }
}
