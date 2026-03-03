package com.weixiao.utils;

import lombok.experimental.UtilityClass;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 路径相关工具方法。
 */
@UtilityClass
public class PathUtils {

    /**
     * 归一化相对路径：反斜杠转正斜杠、去首尾空格、去掉开头的 '/'。
     * 用于统一 diff/checkout 等产生的路径格式。
     *
     * @param path 原始路径，可为 null
     * @return 归一化后的路径，null 时返回 ""
     */
    public static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace('\\', '/').trim();
        return p.startsWith("/") ? p.substring(1) : p;
    }

    public static int pathDepth(String path) {
        if (path == null || path.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                n++;
            }
        }
        return n;
    }

    /**
     * e.g.
     * input : a/b/c.txt
     * 返回: a 和 a/b
     *
     * @param pathStr 路径
     * @return 返回所有父目录
     */
    public static List<String> getAllParentDir(String pathStr) {
        List<String> result = new ArrayList<>();
        if (pathStr == null || pathStr.isEmpty()) {
            return result;
        }

        Path path = Paths.get(pathStr);
        Path parent = path.getParent();

        if (parent == null) {
            return result;
        }

        Path root = parent.getRoot();
        // 循环遍历每一层目录名
        for (int i = 1; i <= parent.getNameCount(); i++) {
            Path subPath = parent.subpath(0, i);
            if (root != null) {
                result.add(root.resolve(subPath).normalize().toString());
            } else {
                result.add(subPath.toString().replace('\\', '/'));
            }
        }

        return result;
    }

}
