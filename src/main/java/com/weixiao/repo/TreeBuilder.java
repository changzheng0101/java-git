package com.weixiao.repo;

import com.weixiao.obj.Tree;
import com.weixiao.obj.TreeEntry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 从 index 条目构建并写入 tree 对象，返回根 tree oid。
 */
public final class TreeBuilder {

    private TreeBuilder() {
    }

    public static String buildTreeFromIndex(List<Index.Entry> indexEntries) throws IOException {
        return buildTreeFromIndex(indexEntries, "");
    }

    /**
     * 从 index 的扁平 path 列表递归构建 Tree。
     * prefix 为当前目录相对仓库根的路径（如 "" 或 "dir/"），只处理 path.startsWith(prefix) 的条目。
     * 返回当前目录的 tree oid。
     */
    private static String buildTreeFromIndex(List<Index.Entry> indexEntries, String prefix) throws IOException {
        List<TreeEntry> entries = new ArrayList<>();
        String prefixSlash = prefix.isEmpty() ? "" : prefix;

        Set<String> dirNames = new HashSet<>();
        for (Index.Entry e : indexEntries) {
            if (!e.getPath().startsWith(prefixSlash)) {
                continue;
            }
            String local = prefixSlash.isEmpty() ? e.getPath() : e.getPath().substring(prefixSlash.length());
            if (local.isEmpty()) {
                continue;
            }
            if (!local.contains("/")) {
                entries.add(new TreeEntry(e.getMode(), local, e.getOid()));
            } else {
                dirNames.add(local.substring(0, local.indexOf('/')));
            }
        }

        for (String dirName : dirNames) {
            String subPrefix = prefixSlash + dirName + "/";
            String childTreeOid = buildTreeFromIndex(indexEntries, subPrefix);
            entries.add(new TreeEntry("40000", dirName, childTreeOid));
        }

        Tree tree = new Tree(entries);
        return Repository.INSTANCE.getDatabase().store(tree);
    }
}

