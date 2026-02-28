package com.weixiao.diff;

import com.weixiao.obj.Commit;
import com.weixiao.obj.GitObject;
import com.weixiao.obj.Tree;
import com.weixiao.obj.TreeEntry;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     *
     * @param repo      仓库（用于 load 对象）
     * @param commitIdA 基准 commit 的 40 位 oid
     * @param commitIdB 对比 commit 的 40 位 oid
     * @param prefix    从仓库根到目标目录的路径，空串表示根目录
     * @return DiffEntry 列表（每项含 status、entryA、entryB）
     */
    public static List<DiffEntry> diff(Repository repo, String commitIdA, String commitIdB, String prefix)
            throws IOException {
        ObjectDatabase db = repo.getDatabase();
        Tree treeA = getTreeAtPrefix(db, getCommitRootTreeOid(db, commitIdA), prefix);
        Tree treeB = getTreeAtPrefix(db, getCommitRootTreeOid(db, commitIdB), prefix);
        return compareTrees(treeA, treeB);
    }

    /**
     * 从 commit 获取根 tree 的 oid。
     */
    private static String getCommitRootTreeOid(ObjectDatabase db, String commitId) throws IOException {
        GitObject obj = db.load(commitId);
        if (!"commit".equals(obj.getType())) {
            throw new IOException("not a commit: " + commitId);
        }
        Commit commit = (Commit) obj;
        return commit.getTreeOid();
    }

    /**
     * 根据 prefix 从根 tree 向下解析到目标 tree；prefix 为空则返回根 tree。
     */
    private static Tree getTreeAtPrefix(ObjectDatabase db, String rootTreeOid, String prefix) throws IOException {
        if (prefix == null || prefix.trim().isEmpty()) {
            return loadTree(db, rootTreeOid);
        }
        String[] segments = prefix.trim().split("/");
        String currentTreeOid = rootTreeOid;
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            Tree tree = loadTree(db, currentTreeOid);
            TreeEntry entry = findEntry(tree, segment);
            if (entry == null) {
                throw new IOException("path not found in tree: " + prefix + " (missing: " + segment + ")");
            }
            if (!"40000".equals(entry.getMode())) {
                throw new IOException("not a directory in path: " + segment);
            }
            currentTreeOid = entry.getOid();
        }
        return loadTree(db, currentTreeOid);
    }

    private static Tree loadTree(ObjectDatabase db, String treeOid) throws IOException {
        GitObject obj = db.load(treeOid);
        if (!"tree".equals(obj.getType())) {
            throw new IOException("expected tree, got " + obj.getType() + ": " + treeOid);
        }
        return (Tree) obj;
    }

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
     */
    private static List<DiffEntry> compareTrees(Tree treeA, Tree treeB) {
        Map<String, TreeEntry> byNameA = new LinkedHashMap<>();
        for (TreeEntry e : treeA.getEntries()) {
            byNameA.put(e.getName(), e);
        }
        Map<String, TreeEntry> byNameB = new LinkedHashMap<>();
        for (TreeEntry e : treeB.getEntries()) {
            byNameB.put(e.getName(), e);
        }

        List<DiffEntry> entries = new ArrayList<>();

        for (Map.Entry<String, TreeEntry> e : byNameB.entrySet()) {
            TreeEntry entryB = e.getValue();
            TreeEntry entryA = byNameA.get(e.getKey());
            if (entryA == null) {
                entries.add(new DiffEntry(DiffEntry.DiffStatus.CREATED, null, entryB));
            } else if (!sameEntry(entryA, entryB)) {
                entries.add(new DiffEntry(DiffEntry.DiffStatus.MODIFIED, entryA, entryB));
            }
        }
        for (Map.Entry<String, TreeEntry> e : byNameA.entrySet()) {
            if (!byNameB.containsKey(e.getKey())) {
                entries.add(new DiffEntry(DiffEntry.DiffStatus.DELETED, e.getValue(), null));
            }
        }

        return entries;
    }

    /** 比较 oid 与 mode 是否一致。 */
    private static boolean sameEntry(TreeEntry a, TreeEntry b) {
        return a.getOid().equals(b.getOid()) && a.getMode().equals(b.getMode());
    }
}
