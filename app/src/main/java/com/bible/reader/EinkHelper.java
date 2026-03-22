package com.bible.reader;

import android.view.View;
import java.lang.reflect.Method;

/**
 * Helper for ONYX BOOX e-ink display optimization.
 * Uses reflection to avoid compile-time dependency on BOOX SDK.
 * Silently fails on non-BOOX devices.
 */
public class EinkHelper {

    private static boolean initialized = false;
    private static boolean available = false;

    // Reflected classes and methods
    private static Class<?> epdControllerClass;
    private static Class<?> updateModeClass;
    private static Class<?> updateSchemeClass;
    private static Method setViewDefaultUpdateMode;
    private static Method resetViewUpdateMode;
    private static Method setSystemUpdateModeAndScheme;
    private static Method clearSystemUpdateModeAndScheme;
    private static Object updateModeGU;      // partial update, less flash
    private static Object updateModeGU_FAST; // fast partial
    private static Object updateModeDU;      // direct update, no flash
    private static Object updateModeGC;      // full quality
    private static Object updateModeAnim;    // animation quality (A2-like)
    private static Object schemeQueueAndMerge;

    static {
        init();
    }

    private static void init() {
        if (initialized) return;
        initialized = true;

        // Try different class paths used across BOOX firmware versions
        String[] classNames = {
                "com.onyx.android.sdk.device.EpdController",
                "com.onyx.android.sdk.api.device.epd.EpdController",
                "android.hardware.EpdController",
        };

        for (String className : classNames) {
            try {
                epdControllerClass = Class.forName(className);
                break;
            } catch (ClassNotFoundException e) {
                // try next
            }
        }

        if (epdControllerClass == null) return;

        // Find UpdateMode enum - it's a nested enum in EpdController
        String[] modeClassNames = {
                epdControllerClass.getName() + "$UpdateMode",
                "com.onyx.android.sdk.device.EpdController$UpdateMode",
                "com.onyx.android.sdk.api.device.epd.UpdateMode",
        };

        for (String modeName : modeClassNames) {
            try {
                updateModeClass = Class.forName(modeName);
                break;
            } catch (ClassNotFoundException e) {
                // try next
            }
        }

        if (updateModeClass == null || !updateModeClass.isEnum()) return;

        try {
            // Get update mode constants
            Object[] modes = updateModeClass.getEnumConstants();
            for (Object mode : modes) {
                String name = mode.toString();
                if ("GU".equals(name)) updateModeGU = mode;
                else if ("GU_FAST".equals(name)) updateModeGU_FAST = mode;
                else if ("DU".equals(name)) updateModeDU = mode;
                else if ("GC".equals(name)) updateModeGC = mode;
                else if ("ANIMATION_QUALITY".equals(name)) updateModeAnim = mode;
            }
            if (updateModeAnim == null) updateModeAnim = updateModeGU;

            // Get methods
            setViewDefaultUpdateMode = epdControllerClass.getMethod(
                    "setViewDefaultUpdateMode", View.class, updateModeClass);
            resetViewUpdateMode = epdControllerClass.getMethod(
                    "resetViewUpdateMode", View.class);

            // Try to get system-wide update mode control
            try {
                String[] schemeClassNames = {
                        epdControllerClass.getName() + "$UpdateScheme",
                        "com.onyx.android.sdk.device.EpdController$UpdateScheme",
                };
                for (String sn : schemeClassNames) {
                    try { updateSchemeClass = Class.forName(sn); break; }
                    catch (ClassNotFoundException e) {}
                }
                if (updateSchemeClass != null) {
                    for (Object s : updateSchemeClass.getEnumConstants()) {
                        if ("QUEUE_AND_MERGE".equals(s.toString())) schemeQueueAndMerge = s;
                    }
                    setSystemUpdateModeAndScheme = epdControllerClass.getMethod(
                            "setSystemUpdateModeAndScheme",
                            updateModeClass, updateSchemeClass, int.class);
                    clearSystemUpdateModeAndScheme = epdControllerClass.getMethod(
                            "clearSystemUpdateModeAndScheme");
                }
            } catch (Exception e) {
                // optional, ignore
            }

            available = true;
        } catch (Exception e) {
            available = false;
        }
    }

    /**
     * Check if BOOX e-ink API is available on this device.
     */
    public static boolean isAvailable() {
        return available;
    }

    /**
     * Set a view to use partial update mode (GU) — less flashing, good for text.
     */
    public static void setPartialUpdate(View view) {
        if (!available || updateModeGU == null) return;
        try {
            setViewDefaultUpdateMode.invoke(null, view, updateModeGU);
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * Set a view to use animation-quality mode — fastest, minimal flash.
     * Good for scrolling and quick navigation.
     */
    public static void setFastUpdate(View view) {
        if (!available || updateModeAnim == null) return;
        try {
            setViewDefaultUpdateMode.invoke(null, view, updateModeAnim);
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * Reset view to default (full quality) update mode.
     */
    public static void resetUpdate(View view) {
        if (!available) return;
        try {
            resetViewUpdateMode.invoke(null, view);
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * Enable GU (partial update) mode globally for the whole app.
     * Reduces full-screen flashing on e-ink. Call from Application.onCreate().
     */
    public static void enableGlobalPartialUpdate() {
        if (!available) return;
        if (setSystemUpdateModeAndScheme != null && schemeQueueAndMerge != null && updateModeGU != null) {
            try {
                setSystemUpdateModeAndScheme.invoke(null, updateModeGU, schemeQueueAndMerge, Integer.MAX_VALUE);
            } catch (Exception e) {}
        }
    }

    /**
     * Set system-wide fast update mode for page turns.
     * Call before setSelectionFromTop, call clearPageTurnMode after.
     */
    public static void setPageTurnMode() {
        if (!available) return;
        // Try system-wide mode first (most effective)
        if (setSystemUpdateModeAndScheme != null && schemeQueueAndMerge != null) {
            Object mode = updateModeGU_FAST != null ? updateModeGU_FAST :
                          updateModeDU != null ? updateModeDU : updateModeGU;
            if (mode != null) {
                try {
                    setSystemUpdateModeAndScheme.invoke(null, mode, schemeQueueAndMerge, Integer.MAX_VALUE);
                    return;
                } catch (Exception e) {}
            }
        }
    }

    /**
     * Clear system-wide page turn mode, restore normal rendering.
     */
    public static void clearPageTurnMode() {
        if (!available) return;
        if (clearSystemUpdateModeAndScheme != null) {
            try {
                clearSystemUpdateModeAndScheme.invoke(null);
            } catch (Exception e) {}
        }
    }
}
