package com.weixiao.diff;

import com.weixiao.diff.DiffEntry.DiffStatus;
import com.weixiao.obj.Tree;
import com.weixiao.obj.TreeEntry;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Repository;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.*;

import static com.weixiao.utils.Constants.*;

/**
 * 比较两个 commit 在指定目录（prefix）下的 tree 差异。
 * 参数：commitIdA、commitIdB、prefix（从 git 根到该目录的路径）。
 * 结果：A 无 B 有（新增）、A 有 B 无（删除）、两边都有但 hash 或 mode 不同（修改）。
 */
public final class TreeDiff {

    private static ObjectDatabase db = Repository.INSTANCE.getDatabase();

    private TreeDiff() {
    }

    /**
     * 计算两个 commit 在 prefix 目录下的 tree diff。
     *
     * @param commitIdA 基准 commit 的 40 位 oid
     * @param commitIdB 对比 commit 的 40 位 oid
     * @param prefix    从仓库根到目标目录的路径，空串表示根目录
     * @return DiffEntry 列表（每项含 status、entryA、entryB）
     */
    public static List<DiffEntry> diff(String commitIdA, String commitIdB, String prefix)
            throws IOException {
        Tree treeA = getTreeAtPrefix(db, db.loadCommit(commitIdA).getTreeOid(), prefix);
        Tree treeB = getTreeAtPrefix(db, db.loadCommit(commitIdB).getTreeOid(), prefix);
        return compareTrees(treeA, treeB, prefix);
    }

    // 待删除开始
    /**
     * 比较两个 commit 的完整 tree，生成变更列表与目标 index 列表（均带完整路径），供 checkout 等使用。
     */
    public static CompareCommitsResult compareCommits(Repository repo, String commitIdA, String commitIdB)
            throws IOException {
        Map<String, String> currentPathToOid = new LinkedHashMap<>();
        Map<String, String> currentPathToMode = new LinkedHashMap<>();
        if (commitIdA != null && !commitIdA.isEmpty()) {
            repo.collectCommitTreeTo(commitIdA, currentPathToOid, currentPathToMode);
        }
        Map<String, String> targetPathToOid = new LinkedHashMap<>();
        Map<String, String> targetPathToMode = new LinkedHashMap<>();
        repo.collectCommitTreeTo(commitIdB, targetPathToOid, targetPathToMode);

        List<DiffEntry> changes = new ArrayList<>();
        for (Map.Entry<String, String> e : targetPathToOid.entrySet()) {
            String path = e.getKey();
            String oid = e.getValue();
            String mode = targetPathToMode.get(path);
            String curOid = currentPathToOid.get(path);
            String curMode = currentPathToMode.get(path);
            String segment = segmentName(path);
            if (curOid == null) {
                changes.add(new DiffEntry(DiffStatus.CREATED, null,
                        new TreeEntry(mode, segment, oid), path));
            } else if (!oid.equals(curOid) || !mode.equals(curMode)) {
                changes.add(new DiffEntry(DiffStatus.MODIFIED,
                        new TreeEntry(curMode, segment, curOid),
                        new TreeEntry(mode, segment, oid), path));
            }
        }
        for (Map.Entry<String, String> e : currentPathToOid.entrySet()) {
            String path = e.getKey();
            if (!targetPathToOid.containsKey(path)) {
                String curOid = e.getValue();
                String curMode = currentPathToMode.get(path);
                String segment = segmentName(path);
                changes.add(new DiffEntry(DiffStatus.DELETED,
                        new TreeEntry(curMode, segment, curOid), null, path));
            }
        }

        List<DiffEntry> targetIndexEntries = new ArrayList<>();
        for (Map.Entry<String, String> e : targetPathToOid.entrySet()) {
            String path = e.getKey();
            String oid = e.getValue();
            String mode = targetPathToMode.get(path);
            String segment = segmentName(path);
            targetIndexEntries.add(new DiffEntry(DiffStatus.CREATED, null,
                    new TreeEntry(mode, segment, oid), path));
        }
        return new CompareCommitsResult(changes, targetIndexEntries);
    }

    private static String segmentName(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    /**
     * compareCommits 的返回：变更列表 + 目标 commit 的 index 条目列表（均为 DiffEntry，带 path）。
     */
    public static final class CompareCommitsResult {

        private final List<DiffEntry> changes;
        private final List<DiffEntry> targetIndexEntries;

        public CompareCommitsResult(List<DiffEntry> changes, List<DiffEntry> targetIndexEntries) {
            this.changes = changes;
            this.targetIndexEntries = targetIndexEntries;
        }

        public List<DiffEntry> getChanges() {
            return changes;
        }

        public List<DiffEntry> getTargetIndexEntries() {
            return targetIndexEntries;
        }
    }
    // 待删除结束



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
     * treeA 和 treeB 对应的前缀是prefix
     * 也就是说prefix为treeA和treeB对应的文件夹名称
     */
    private static List<DiffEntry> compareTrees(Tree treeA, Tree treeB, String prefix) throws IOException {
        List<DiffEntry> diffEntries = new ArrayList<>();

        if (treeA == null) {
            for (TreeEntry entry : treeB.getEntries()) {
                if (entry.isDirectory()) {
                    diffEntries.addAll(compareTrees(null, db.loadTree(entry.getOid()), prefix + FILE_SEPARATOR + entry.getName()));
                } else {
                    diffEntries.add(new DiffEntry(DiffStatus.CREATED, null, entry, prefix + FILE_SEPARATOR + entry.getName()));
                }
            }
            return diffEntries;
        }
        if (treeB == null) {
            for (TreeEntry entry : treeA.getEntries()) {
                if (entry.isDirectory()) {
                    diffEntries.addAll(compareTrees(db.loadTree(entry.getOid()), null, prefix + FILE_SEPARATOR + entry.getName()));
                } else {
                    diffEntries.add(new DiffEntry(DiffStatus.DELETED, entry, null, prefix + FILE_SEPARATOR + entry.getName()));
                }
            }
            return diffEntries;
        }

        for (TreeEntry entryB : treeB.getEntries()) {
            String path = prefix + FILE_SEPARATOR + entryB.getName();
            TreeEntry entryA = findEntry(treeA, entryB.getName());
            if (entryB.isDirectory() && entryA == null) {
                // 这里考虑 B有 A没有的文件夹
                diffEntries.addAll(compareTrees(null, db.loadTree(entryB.getOid()), path));
            }
            if (entryB.isDirectory() && entryA.isDirectory()) {
                // 下面会考虑 这里先略过
                continue;
            }


            if (entryA == null) {
                diffEntries.add(new DiffEntry(DiffStatus.CREATED, null, entryB, path));
            } else if (!(Objects.equals(entryA, entryB))) {
                diffEntries.add(new DiffEntry(DiffStatus.MODIFIED, entryA, entryB, path));
            }
        }

        for (TreeEntry entryA : treeA.getEntries()) {
            String path = prefix + FILE_SEPARATOR + entryA.getName();

            if (entryA.isDirectory()) {
                // 这里考虑了 A有 B有和没有两种情况的文件夹
                diffEntries.addAll(compareTrees(
                        db.loadTree(entryA.getOid()),
                        findEntry(treeB, entryA.getName()) == null ? null : db.loadTree(findEntry(treeB, entryA.getName()).getOid()),
                        path
                ));
                continue;
            }

            if (findEntry(treeB, entryA.getName()) == null) {
                diffEntries.add(new DiffEntry(DiffStatus.DELETED, entryA, null, path));
            }
        }

        return diffEntries;
    }


}
