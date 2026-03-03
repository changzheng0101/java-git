package com.weixiao.diff;

import com.weixiao.diff.DiffEntry.DiffStatus;
import com.weixiao.obj.Tree;
import com.weixiao.obj.TreeEntry;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Repository;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.*;

import static com.weixiao.utils.Constants.*;

/**
 * 比较两个 commit 在指定目录（prefix）下的 tree 差异。
 * 参数：commitIdA、commitIdB、prefix（从 git 根到该目录的路径）。
 * 结果：A 无 B 有（新增）、A 有 B 无（删除）、两边都有但 hash 或 mode 不同（修改）。
 */
public final class TreeDiff {

    private TreeDiff() {
    }

    /**
     * 计算两个 commit 在 prefix 目录下的 tree diff。
     * commitIdA 为 null 或空时视为空 tree（如无 HEAD 时 checkout）。
     *
     * @param commitIdA 基准 commit 的 40 位 oid，可为 null
     * @param commitIdB 对比 commit 的 40 位 oid
     * @param prefix    从仓库根到目标目录的路径，空串表示根目录
     * @return DiffEntry 列表（每项含 status、entryA、entryB）
     */
    public static List<DiffEntry> diff(String commitIdA, String commitIdB, String prefix)
            throws IOException {
        ObjectDatabase db = Repository.INSTANCE.getDatabase();
        Tree treeA = getTreeAtPrefix(db, db.loadCommit(commitIdA).getTreeOid(), prefix);
        Tree treeB = getTreeAtPrefix(db, db.loadCommit(commitIdB).getTreeOid(), prefix);
        return compareTrees(db, treeA, treeB, prefix);
    }


    /**
     * 根据 prefix 从根 tree 向下解析到目标 tree；prefix 为空则返回根 tree。
     * 从rootTreeOid开始，解析prefix对应的Tree
     * 例如  /test/hello
     * 将会从根目录下解析/test/hello之后返回hello文件夹对应的Tree
     * 只保留不相同的文件，不保留不相同的文件夹
     */
    private static Tree getTreeAtPrefix(ObjectDatabase db, String rootTreeOid, String prefix) throws IOException {
        if (prefix == null || prefix.trim().isEmpty()) {
            return db.loadTree(rootTreeOid);
        }
        String[] directoryNames = prefix.trim().split(FileSystems.getDefault().getSeparator());
        String currentTreeOid = rootTreeOid;
        for (String dirName : directoryNames) {
            if (dirName.isEmpty()) {
                continue;
            }
            Tree tree = db.loadTree(currentTreeOid);
            TreeEntry entry = findEntry(tree, dirName);
            if (entry == null) {
                throw new IOException("path not found in tree: " + prefix + " (missing: " + dirName + ")");
            }
            if (!"40000".equals(entry.getMode())) {
                throw new IOException("not a directory in path: " + dirName);
            }
            currentTreeOid = entry.getOid();
        }

        return db.loadTree(currentTreeOid);
    }


    /**
     * @param tree Tree
     * @param name 要找的文件夹
     * @return 找到对应文件夹的名字，就返回该文件夹对应的TreeEntry，否则返回null
     */
    private static TreeEntry findEntry(Tree tree, String name) {
        for (TreeEntry e : tree.getEntries()) {
            if (e.getName().equals(name)) {
                return e;
            }
        }
        return null;
    }

    /**
     * 比较两个 tree 的直接子项：按 name 区分 CREATED、DELETED、MODIFIED（比较 oid 与 mode）。
     * treeA 和 treeB 对应的前缀是 prefix。
     */
    private static List<DiffEntry> compareTrees(ObjectDatabase db, Tree treeA, Tree treeB, String prefix)
            throws IOException {
        List<DiffEntry> diffEntries = new ArrayList<>();

        if (treeA == null) {
            for (TreeEntry entry : treeB.getEntries()) {
                if (entry.isDirectory()) {
                    diffEntries.addAll(compareTrees(db, null, db.loadTree(entry.getOid()),
                            prefix + FILE_SEPARATOR + entry.getName()));
                } else {
                    diffEntries.add(new DiffEntry(DiffStatus.CREATED, null, entry,
                            prefix + FILE_SEPARATOR + entry.getName()));
                }
            }
            return diffEntries;
        }
        if (treeB == null) {
            for (TreeEntry entry : treeA.getEntries()) {
                if (entry.isDirectory()) {
                    diffEntries.addAll(compareTrees(db, db.loadTree(entry.getOid()), null,
                            prefix + FILE_SEPARATOR + entry.getName()));
                } else {
                    diffEntries.add(new DiffEntry(DiffStatus.DELETED, entry, null,
                            prefix + FILE_SEPARATOR + entry.getName()));
                }
            }
            return diffEntries;
        }

        for (TreeEntry entryB : treeB.getEntries()) {
            String path = prefix + FILE_SEPARATOR + entryB.getName();
            TreeEntry entryA = findEntry(treeA, entryB.getName());
            if (entryB.isDirectory() && entryA == null) {
                diffEntries.addAll(compareTrees(db, null, db.loadTree(entryB.getOid()), path));
            }
            if (entryB.isDirectory() && entryA != null && entryA.isDirectory()) {
                continue;
            }

            if (entryA == null) {
                diffEntries.add(new DiffEntry(DiffStatus.CREATED, null, entryB, path));
            } else if (!entryB.isDirectory() && !Objects.equals(entryA, entryB)) {
                diffEntries.add(new DiffEntry(DiffStatus.MODIFIED, entryA, entryB, path));
            }
        }

        for (TreeEntry entryA : treeA.getEntries()) {
            String path = prefix + FILE_SEPARATOR + entryA.getName();

            if (entryA.isDirectory()) {
                TreeEntry bEntry = findEntry(treeB, entryA.getName());
                diffEntries.addAll(compareTrees(db,
                        db.loadTree(entryA.getOid()),
                        bEntry == null ? null : db.loadTree(bEntry.getOid()),
                        path));
                continue;
            }

            if (findEntry(treeB, entryA.getName()) == null) {
                diffEntries.add(new DiffEntry(DiffStatus.DELETED, entryA, null, path));
            }
        }

        return diffEntries;
    }


}
