package com.weixiao.diff;

import com.weixiao.diff.DiffEntry.DiffStatus;
import com.weixiao.obj.TreeEntry;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Repository;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 比较两个 commit 在根目录下的 tree 差异。
 * 参数：commitIdA、commitIdB。
 * 结果：A 无 B 有（新增）、A 有 B 无（删除）、两边都有但 hash 或 mode 不同（修改）。
 */
@SuppressWarnings("DataFlowIssue")
public final class TreeDiff {

    private final ObjectDatabase database;
    private final List<DiffEntry> changes = new ArrayList<>();

    private TreeDiff(ObjectDatabase database) {
        this.database = database;
    }

    /**
     * 计算两个 commit 在根目录下的 tree diff。
     *
     * @param commitIdA 基准 commit 的 40 位 oid
     * @param commitIdB 对比 commit 的 40 位 oid
     * @return DiffEntry 列表（每项含 status、entryA、entryB、path 为 Path）
     */
    public static List<DiffEntry> diff(String commitIdA, String commitIdB) throws IOException {
        ObjectDatabase db = Repository.INSTANCE.getDatabase();
        String treeOidA = db.loadCommit(commitIdA).getTreeOid();
        String treeOidB = db.loadCommit(commitIdB).getTreeOid();
        Path base = Paths.get("");

        TreeDiff treeDiff = new TreeDiff(db);
        treeDiff.compareOids(treeOidA, treeOidB, base);
        return treeDiff.changes;
    }

    /**
     * 比较两个 oid（可为 commit/tree oid）对应树结构在 prefix 下的差异。
     * 先检测删除，再检测新增，目录差异通过递归比较子树展开。
     */
    private void compareOids(String treeAOid, String treeBOid, Path prefix) throws IOException {
        if (Objects.equals(treeAOid, treeBOid)) {
            return;
        }
        Map<String, TreeEntry> aEntries = database.loadTree(treeAOid).toEntryMap();
        Map<String, TreeEntry> bEntries = database.loadTree(treeBOid).toEntryMap();
        detectDeletions(aEntries, bEntries, prefix);
        detectAdditions(aEntries, bEntries, prefix);
    }


    /**
     * 处理 A 侧存在的路径：对比 B 侧同名路径，识别 DELETED / MODIFIED，并递归目录差异。
     */
    private void detectDeletions(Map<String, TreeEntry> aEntries,
                                 Map<String, TreeEntry> bEntries,
                                 Path prefix) throws IOException {
        for (Map.Entry<String, TreeEntry> entry : aEntries.entrySet()) {
            String name = entry.getKey();
            TreeEntry a = entry.getValue();
            TreeEntry b = bEntries.get(name);
            if (Objects.equals(a, b)) {
                continue;
            }

            Path path = prefix.resolve(name);
            String treeOidA = (a != null && a.isDirectory()) ? a.getOid() : null;
            String treeOidB = (b != null && b.isDirectory()) ? b.getOid() : null;
            compareOids(treeOidA, treeOidB, path);

            TreeEntry blobA = (a != null && a.isDirectory()) ? null : a;
            TreeEntry blobB = (b != null && b.isDirectory()) ? null : b;
            if (blobA != null || blobB != null) {
                addBlobChange(path, blobA, blobB);
            }
        }
    }

    /**
     * 处理仅 B 侧新增的路径：目录递归展开，文件直接记为 CREATED。
     */
    private void detectAdditions(Map<String, TreeEntry> aEntries,
                                 Map<String, TreeEntry> bEntries,
                                 Path prefix) throws IOException {
        for (Map.Entry<String, TreeEntry> entry : bEntries.entrySet()) {
            String name = entry.getKey();
            TreeEntry b = entry.getValue();
            TreeEntry a = aEntries.get(name);
            if (a != null) {
                continue;
            }

            Path path = prefix.resolve(name);
            if (b.isDirectory()) {
                compareOids(null, b.getOid(), path);
            } else {
                addBlobChange(path, null, b);
            }
        }
    }

    private void addBlobChange(Path path, TreeEntry blobA, TreeEntry blobB) {
        DiffStatus status = blobA == null ? DiffStatus.CREATED
                : (blobB == null ? DiffStatus.DELETED : DiffStatus.MODIFIED);
        changes.add(new DiffEntry(status, blobA, blobB, path));
    }
}
