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
    private static Method setViewDefaultUpdateMode;
    private static Method resetViewUpdateMode;
    private static Object updateModeGU;      // partial update, less flash
    private static Object updateModeGC;      // full quality
    private static Object updateModeAnim;    // animation quality (A2-like)

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
                else if ("GC".equals(name)) updateModeGC = mode;
                else if ("ANIMATION_QUALITY".equals(name)) updateModeAnim = mode;
            }
            // Fallback: if ANIMATION_QUALITY not found, use GU
            if (updateModeAnim == null) updateModeAnim = updateModeGU;

            // Get methods
            setViewDefaultUpdateMode = epdControllerClass.getMethod(
                    "setViewDefaultUpdateMode", View.class, updateModeClass);
            resetViewUpdateMode = epdControllerClass.getMethod(
                    "resetViewUpdateMode", View.class);

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
}
