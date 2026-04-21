package com.weixiao.utils;

import lombok.experimental.UtilityClass;

/**
 * 通用 ANSI 颜色工具类，供整个项目输出使用。
 * 颜色约定尽量与 Git 一致：
 * - 黄色：hash / oid
 * - 加粗：重要 meta 行
 * - 红：删除，绿：新增，青：hunk 头等。
 */
@UtilityClass
public class Color {


    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String RED = "\033[31m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
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

    public static String yellow(String s) {
        return YELLOW + s + RESET;
    }

    public static String cyan(String s) {
        return CYAN + s + RESET;
    }

    public static String deletion(String line) {
        return RED + line + RESET;
    }

    public static String insertion(String line) {
        return GREEN + line + RESET;
    }

    public static String context(String line) {
        return line;
    }
}
