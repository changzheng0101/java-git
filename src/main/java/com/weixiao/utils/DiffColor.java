package com.weixiao.utils;

import lombok.experimental.UtilityClass;

/**
 * 兼容旧代码的 diff 颜色工具，内部委托给通用 {@link Color}。
 * 新代码请直接使用 {@link Color}。
 */
@UtilityClass
public class DiffColor {

    public static String reset() {
        return Color.reset();
    }

    public static String bold(String s) {
        return Color.bold(s);
    }

    public static String red(String s) {
        return Color.red(s);
    }

    public static String green(String s) {
        return Color.green(s);
    }

    public static String cyan(String s) {
        return Color.cyan(s);
    }

    public static String deletion(String line) {
        return Color.deletion(line);
    }

    public static String insertion(String line) {
        return Color.insertion(line);
    }

    public static String context(String line) {
        return Color.context(line);
    }
}
