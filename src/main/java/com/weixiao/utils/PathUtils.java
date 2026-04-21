package com.weixiao.utils;

import com.google.common.base.Strings;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.nio.file.Files;
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
     * 归一化相对路径：反斜杠转正斜杠、去首尾空格、去掉开头的 '/'、去掉尾部 '/'。
     * 用于统一 diff/checkout 等产生的路径格式。
     *
     * @param path 原始路径，可为 null
     * @return 归一化后的路径，null 时返回 ""
     */
    public static String normalizePath(String path) {
        String p = Strings.nullToEmpty(path).replace('\\', '/').trim();
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    public static int pathDepth(String path) {
        if (Strings.isNullOrEmpty(path)) {
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
     * Path 的深度（目录层级，即斜杠数）；null 或空视为 0。与 pathDepth(String) 语义一致。
     */
    public static int pathDepth(Path path) {
        if (path == null) {
            return 0;
        }
        int n = path.getNameCount();
        return n <= 1 ? 0 : n - 1;
    }

    /**
     * 归一化 Path 为相对路径字符串（反斜杠转正斜杠、去首尾空格、去掉开头 '/'）。
     */
    public static String normalizePath(Path path) {
        return path == null ? "" : normalizePath(path.toString());
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
        if (Strings.isNullOrEmpty(pathStr)) {
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

    /**
     * 判断目录树是否“只包含空目录，不包含任何文件”。
     * <p>
     * 仅当 path 存在且为目录，且其目录树中不存在普通文件时返回 true。
     */
    public static boolean containsOnlyEmptyDirectories(Path path) throws IOException {
        if (path == null || !Files.isDirectory(path)) {
            return false;
        }
        for (Path child : com.weixiao.repo.Workspace.listEntries(path)) {
            if (Files.isRegularFile(child)) {
                return false;
            }
            if (Files.isDirectory(child) && !containsOnlyEmptyDirectories(child)) {
                return false;
            }
        }
        return true;
    }
}
