package org.telegram.messenger;

import android.view.Window;
import android.view.WindowManager;

import java.util.HashMap;

import tw.nekomimi.nekogram.NekoXConfig;

public class FlagSecureReason {

    private static HashMap<Window, Integer> currentSecureReasons;

    private final Window window;
    private final FlagSecureCondition condition;

    public FlagSecureReason(Window window, FlagSecureCondition condition) {
        this.window = window;
        this.condition = condition;
    }

    private boolean attached = false;
    private boolean value = false;

    public void invalidate() {
        boolean newValue = attached && condition != null && condition.run();
        if (newValue != value) {
            update((value = newValue) ? +1 : -1);
        }
    }

    public void attach() {
        if (attached) {
            return;
        }
        attached = true;
        invalidate();
    }

    public void detach() {
        if (!attached) {
            return;
        }
        attached = false;
        invalidate();
    }

    private void update(int add) {
        if (currentSecureReasons == null) {
            currentSecureReasons = new HashMap<>();
        }

        Integer count = currentSecureReasons.get(window);
        int newCount = Math.max(0, (count == null ? 0 : count) + add);
        if (newCount <= 0) {
            currentSecureReasons.remove(window);
        } else {
            currentSecureReasons.put(window, newCount);
        }

        updateWindowSecure(window);
    }

    private static void updateWindowSecure(Window window) {
        if (window == null) {
            return;
        }

        if (isSecuredNow(window)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            AndroidUtilities.logFlagSecure();
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            AndroidUtilities.logFlagSecure();
        }
    }

    public static boolean isSecuredNow(Window window) {
        return currentSecureReasons != null && currentSecureReasons.get(window) != null && !NekoXConfig.disableFlagSecure;
    }

    public interface FlagSecureCondition {
        boolean run();
    }

}
