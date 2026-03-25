package com.weixiao.utils;

/**
 * 数组查找工具。
 */
public final class ArrayUtils {

    private ArrayUtils() {
    }

    /**
     * 在 byte 数组中查找目标值的首次出现位置。
     */
    @SuppressWarnings("unused")
    public static int indexOf(byte[] array, byte target) {
        return indexOf(array, target, 0);
    }

    /**
     * 在 byte 数组中从指定位置开始查找目标值的首次出现位置。
     */
    public static int indexOf(byte[] array, byte target, int from) {
        if (array == null || array.length == 0) {
            return -1;
        }
        int start = Math.max(0, from);
        for (int i = start; i < array.length; i++) {
            if (array[i] == target) {
                return i;
            }
        }
        return -1;
    }
}
