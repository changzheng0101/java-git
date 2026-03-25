package com.weixiao.diff;

import com.weixiao.diff.DiffEntry.DiffStatus;
import com.weixiao.obj.Commit;
import com.weixiao.obj.Tree;
import com.weixiao.obj.TreeEntry;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Repository;

import javax.annotation.Nonnull;
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
    public static List<DiffEntry> diff(@Nonnull String commitIdA, @Nonnull String commitIdB, Path prefix)
            throws IOException {
        ObjectDatabase db = Repository.INSTANCE.getDatabase();
        Commit ca = db.loadCommit(commitIdA);
        Commit cb = db.loadCommit(commitIdB);
        Tree treeA = db.loadTree(ca.getTreeOid());
        Tree treeB = db.loadTree(cb.getTreeOid());
        return compareTrees(treeA, treeB, prefix);
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
    private static List<DiffEntry> compareTrees(Tree treeA, Tree treeB, Path treePath)
            throws IOException {
        ObjectDatabase db = Repository.INSTANCE.getDatabase();
        List<DiffEntry> diffEntries = new ArrayList<>();
        Path base = (treePath != null && treePath.getNameCount() > 0) ? treePath : Paths.get("");

        if (treeA == null) {
            for (TreeEntry entry : treeB.getEntries()) {
                Path entryPath = base.resolve(entry.getName());
                if (entry.isDirectory()) {
                    diffEntries.addAll(compareTrees(null, db.loadTree(entry.getOid()), entryPath));
                } else {
                    diffEntries.add(new DiffEntry(DiffStatus.CREATED, null, entry, entryPath));
                }
            }
            return diffEntries;
        }
        if (treeB == null) {
            for (TreeEntry entry : treeA.getEntries()) {
                Path entryPath = base.resolve(entry.getName());
                if (entry.isDirectory()) {
                    diffEntries.addAll(compareTrees(db.loadTree(entry.getOid()), null, entryPath));
                } else {
                    diffEntries.add(new DiffEntry(DiffStatus.DELETED, entry, null, entryPath));
                }
            }
            return diffEntries;
        }

        for (TreeEntry entryB : treeB.getEntries()) {
            Path entryPath = base.resolve(entryB.getName());
            TreeEntry entryA = findEntryByName(treeA, entryB.getName());
            if (entryA == null) {
                if (entryB.isDirectory()) {
                    diffEntries.addAll(compareTrees(null, db.loadTree(entryB.getOid()), entryPath));
                } else {
                    diffEntries.add(new DiffEntry(DiffStatus.CREATED, null, entryB, entryPath));
                }
                continue;
            }

            if (entryA.isDirectory() && entryB.isDirectory()) {
                diffEntries.addAll(compareTrees(db.loadTree(entryA.getOid()), db.loadTree(entryB.getOid()), entryPath));
                continue;
            }

            // A 是目录，B 是文件：目录下文件全部删除，同时该路径新增文件。
            if (entryA.isDirectory()) {
                diffEntries.addAll(compareTrees(db.loadTree(entryA.getOid()), null, entryPath));
                diffEntries.add(new DiffEntry(DiffStatus.CREATED, null, entryB, entryPath));
                continue;
            }

            // A 是文件，B 是目录：该路径文件删除，同时目录下文件全部新增。
            if (entryB.isDirectory()) {
                diffEntries.add(new DiffEntry(DiffStatus.DELETED, entryA, null, entryPath));
                diffEntries.addAll(compareTrees(null, db.loadTree(entryB.getOid()), entryPath));
                continue;
            }

            if (!Objects.equals(entryA, entryB)) {
                diffEntries.add(new DiffEntry(DiffStatus.MODIFIED, entryA, entryB, entryPath));
            }
        }

        for (TreeEntry entryA : treeA.getEntries()) {
            Path childPath = base.resolve(entryA.getName());
            TreeEntry entryB = findEntryByName(treeB, entryA.getName());
            if (entryB == null) {
                if (entryA.isDirectory()) {
                    diffEntries.addAll(compareTrees(db.loadTree(entryA.getOid()), null, childPath));
                } else {
                    diffEntries.add(new DiffEntry(DiffStatus.DELETED, entryA, null, childPath));
                }
            }
        }

        return diffEntries;
    }


}
