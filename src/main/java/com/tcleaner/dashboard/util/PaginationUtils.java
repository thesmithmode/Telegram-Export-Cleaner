package com.tcleaner.dashboard.util;

public final class PaginationUtils {

    private PaginationUtils() {}

    /** Clamps limit/offset-подобные значения в диапазон [1, max]. */
    public static int clamp(int value, int max) {
        return Math.max(1, Math.min(value, max));
    }
}
