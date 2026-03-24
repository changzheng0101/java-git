package com.weixiao.diff;

import com.weixiao.diff.DiffEntry.DiffStatus;
import com.weixiao.obj.Commit;
import com.weixiao.obj.Tree;
import com.weixiao.obj.TreeEntry;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Repository;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

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
     * @param prefix    从仓库根到目标目录的路径，空或根时表示根目录
     * @return DiffEntry 列表（每项含 status、entryA、entryB、path 为 Path）
     */
    public static List<DiffEntry> diff(String commitIdA, String commitIdB, Path prefix)
            throws IOException {
        ObjectDatabase db = Repository.INSTANCE.getDatabase();
        Commit ca = (commitIdA == null || commitIdA.isEmpty()) ? null : db.loadCommit(commitIdA);
        Commit cb = db.loadCommit(commitIdB);
        Tree treeA = (ca == null) ? null : getTreeAtPrefix(db, ca.getTreeOid(), prefix);
        if (cb == null) {
            throw new IOException("not a commit: " + commitIdB);
        }
        Tree treeB = getTreeAtPrefix(db, cb.getTreeOid(), prefix);
        return compareTrees(db, treeA, treeB, prefix);
    }

    /**
     * 根据 prefix 从根 tree 向下解析到目标 tree；prefix 为空则返回根 tree。
     */
    private static Tree getTreeAtPrefix(ObjectDatabase db, String rootTreeOid, Path prefix)
            throws IOException {
        if (prefix == null || prefix.getNameCount() == 0) {
            return db.loadTree(rootTreeOid);
        }
        String currentTreeOid = rootTreeOid;
        for (int i = 0; i < prefix.getNameCount(); i++) {
            String dirName = prefix.getName(i).toString();
            if (dirName.isEmpty()) {
                continue;
            }
            Tree tree = db.loadTree(currentTreeOid);
            TreeEntry entry = findEntryByName(tree, dirName);
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
     * 在 tree 中按名称查找 entry
     * 只要名称相同就会返回，不管返回的是 文件还是文件夹
     */
    private static TreeEntry findEntryByName(Tree tree, String name) {
        for (TreeEntry e : tree.getEntries()) {
            if (Objects.equals(e.getName(), name)) {
                return e;
            }
        }
        return null;
    }

    /**
     * 比较两个 tree 的直接子项：按 name 区分 CREATED、DELETED、MODIFIED（比较 oid 与 mode）。
     */
    private static List<DiffEntry> compareTrees(ObjectDatabase db, Tree treeA, Tree treeB, Path prefix)
            throws IOException {
        List<DiffEntry> diffEntries = new ArrayList<>();
        Path base = (prefix != null && prefix.getNameCount() > 0) ? prefix : Paths.get("");

        if (treeA == null) {
            for (TreeEntry entry : treeB.getEntries()) {
                Path childPath = base.resolve(entry.getName());
                if (entry.isDirectory()) {
                    diffEntries.addAll(compareTrees(db, null, db.loadTree(entry.getOid()), childPath));
                } else {
                    diffEntries.add(new DiffEntry(DiffStatus.CREATED, null, entry, childPath));
                }
            }
            return diffEntries;
        }
        if (treeB == null) {
            for (TreeEntry entry : treeA.getEntries()) {
                Path childPath = base.resolve(entry.getName());
                if (entry.isDirectory()) {
                    diffEntries.addAll(compareTrees(db, db.loadTree(entry.getOid()), null, childPath));
                } else {
                    diffEntries.add(new DiffEntry(DiffStatus.DELETED, entry, null, childPath));
                }
            }
            return diffEntries;
        }

        for (TreeEntry entryB : treeB.getEntries()) {
            Path childPath = base.resolve(entryB.getName());
            TreeEntry entryA = findEntryByName(treeA, entryB.getName());
            if (entryA == null) {
                if (entryB.isDirectory()) {
                    diffEntries.addAll(compareTrees(db, null, db.loadTree(entryB.getOid()), childPath));
                } else {
                    diffEntries.add(new DiffEntry(DiffStatus.CREATED, null, entryB, childPath));
                }
                continue;
            }

            if (entryA.isDirectory() && entryB.isDirectory()) {
                diffEntries.addAll(compareTrees(db, db.loadTree(entryA.getOid()), db.loadTree(entryB.getOid()), childPath));
                continue;
            }

            // A 是目录，B 是文件：目录下文件全部删除，同时该路径新增文件。
            if (entryA.isDirectory()) {
                diffEntries.addAll(compareTrees(db, db.loadTree(entryA.getOid()), null, childPath));
                diffEntries.add(new DiffEntry(DiffStatus.CREATED, null, entryB, childPath));
                continue;
            }

            // A 是文件，B 是目录：该路径文件删除，同时目录下文件全部新增。
            if (entryB.isDirectory()) {
                diffEntries.add(new DiffEntry(DiffStatus.DELETED, entryA, null, childPath));
                diffEntries.addAll(compareTrees(db, null, db.loadTree(entryB.getOid()), childPath));
                continue;
            }

            if (!Objects.equals(entryA, entryB)) {
                diffEntries.add(new DiffEntry(DiffStatus.MODIFIED, entryA, entryB, childPath));
            }
        }

        for (TreeEntry entryA : treeA.getEntries()) {
            Path childPath = base.resolve(entryA.getName());
            TreeEntry entryB = findEntryByName(treeB, entryA.getName());
            if (entryB == null) {
                if (entryA.isDirectory()) {
                    diffEntries.addAll(compareTrees(db, db.loadTree(entryA.getOid()), null, childPath));
                } else {
                    diffEntries.add(new DiffEntry(DiffStatus.DELETED, entryA, null, childPath));
                }
            }
        }

        return diffEntries;
    }


}
