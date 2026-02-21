package com.weixiao.utils;

import lombok.experimental.UtilityClass;

/**
 * diff 输出用 ANSI 颜色（与 Git 一致：meta 加粗、frag 青色、删除红、新增绿）。
 * SGR 代码参考 Git / jit。
 */
@UtilityClass
public class DiffColor {

    private static final String RESET = "\033[0m";
    /**
     * 加粗（用于 meta：diff --git、index、---/+++）
     */
    private static final String BOLD = "\033[1m";
    /**
     * 红色（删除行）
     */
    private static final String RED = "\033[31m";
    /**
     * 绿色（新增行）
     */
    private static final String GREEN = "\033[32m";
    /**
     * 青色（hunk 头 @@ -1,5 +1,6 @@）
     */
    private static final String CYAN = "\033[36m";

    public static String reset() {
        return RESET;
    }

    public static String bold(String s) {
        return BOLD + s + RESET;
    }

    public static String red(String s) {
        return RED + s + RESET;
    }

    public static String green(String s) {
        return GREEN + s + RESET;
    }

    public static String cyan(String s) {
        return CYAN + s + RESET;
    }

    /**
     * 删除行：整行（含前缀 -）标红。
     */
    public static String deletion(String line) {
        return RED + line + RESET;
    }

    /**
     * 新增行：整行（含前缀 +）标绿。
     */
    public static String insertion(String line) {
        return GREEN + line + RESET;
    }

    /**
     * 上下文行：不加色（或可扩展为 dim）。
     */
    public static String context(String line) {
        return line;
    }
}
