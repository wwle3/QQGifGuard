package com.pjz.qqgifguard;

import de.robv.android.xposed.XposedBridge;

/** Thin logging helper with a stable tag for logcat filters. */
final class XLog {
    private static final String TAG = "QQGifGuard";

    private XLog() {
    }

    static void i(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    static void d(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    static void w(String msg) {
        XposedBridge.log(TAG + ": WARN " + msg);
    }

    static void e(String msg, Throwable t) {
        XposedBridge.log(TAG + ": ERROR " + msg);
        XposedBridge.log(t);
    }
}
