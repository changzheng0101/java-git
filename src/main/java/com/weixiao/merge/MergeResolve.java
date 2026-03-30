package com.weixiao.merge;

import com.weixiao.diff.DiffEntry;
import com.weixiao.diff.TreeDiff;
import com.weixiao.obj.Blob;
import com.weixiao.obj.TreeEntry;
import com.weixiao.repo.Index;
import com.weixiao.repo.Migration;
import com.weixiao.repo.Repository;
import com.weixiao.utils.PathUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * Merge 解析与执行：基于 Inputs，把 base->merge 的净变化应用到 workspace 与 index。
 */
public final class MergeResolve {

    private static final String LEFT_NAME = "HEAD";

    private final MergeInputs inputs;
    private final String mergeRevisionName;
    /**
     * 可以直接应用在Workspace和Index上的修改，会包含冲突的文件，但是DiffEntry中的entryB是处理冲突后的结果
     */
    private final List<DiffEntry> cleanDiff = new ArrayList<>();
    private final List<Conflict> conflicts = new ArrayList<>();
    /**
     * 只写入工作区、不写入 index 的重命名冲突文件（例如 f.txt~HEAD）。
     */
    private final Map<String, TreeEntry> untracked = new HashMap<>();
    private Consumer<String> progressListener = s -> {
    };

    public MergeResolve(MergeInputs inputs, String mergeRevisionName) {
        this.inputs = inputs;
        this.mergeRevisionName = mergeRevisionName;
    }

    /**
     * 注册 merge 过程中的进度回调，供命令层输出用户可见信息。
     */
    public void onProgress(Consumer<String> onProgress) {
        this.progressListener = onProgress != null ? onProgress : s -> {
        };
    }

    public void execute() throws IOException {
        prepareTreeDiffs();

        Migration migration = new Migration(cleanDiff);
        migration.applyChanges();

        applyConflictsToIndex();
        writeUntrackedFiles();
    }

    private void prepareTreeDiffs() throws IOException {
        List<DiffEntry> leftDiff = TreeDiff.diff(inputs.getBaseOid(), inputs.getHeadOid());
        List<DiffEntry> rightDiff = TreeDiff.diff(inputs.getBaseOid(), inputs.getMergeOid());

        Map<String, DiffEntry> leftByPath = toPathMap(leftDiff);
        Map<String, DiffEntry> rightByPath = toPathMap(rightDiff);
        cleanDiff.clear();
        conflicts.clear();

        for (DiffEntry right : rightDiff) {
            if (right.getEntryB() != null) {
                fileDirConflictCheck(right.getPath(), leftByPath, LEFT_NAME);
            }
            processRightDiffEntry(right, leftByPath);
        }

        for (DiffEntry left : leftDiff) {
            if (left.getEntryB() != null) {
                fileDirConflictCheck(left.getPath(), rightByPath, mergeRevisionName);
            }
        }
    }

    /**
     * 检测 a/b 和 a/b/c.txt 同时存在的情况
     * b 不能同时为 dir 和 file
     *
     * @param path       要合并的路径，需要确保这个文件的parent dir都为dir
     * @param diffByPath 对应name的diff
     * @param name       只可能为 HEAD 和 即将合并过来的分支名
     */
    private void fileDirConflictCheck(Path path, Map<String, DiffEntry> diffByPath, String name) {
        String normalizedPath = PathUtils.normalizePath(path);
        for (String parentPath : PathUtils.getAllParentDir(normalizedPath)) {
            DiffEntry parentDiff = diffByPath.get(PathUtils.normalizePath(parentPath));
            if (parentDiff == null || parentDiff.getEntryB() == null) {
                continue;
            }

            TreeEntry oldItem = parentDiff.getEntryA();
            TreeEntry newItem = parentDiff.getEntryB();
            conflicts.add(Objects.equals(name, LEFT_NAME) ?
                    new Conflict(parentPath, oldItem, newItem, null) : new Conflict(parentPath, oldItem, null, newItem));
            cleanDiff.removeIf(e -> parentPath.equals(PathUtils.normalizePath(e.getPath())));
            String renamedPath = parentPath + "~" + name;
            untracked.put(renamedPath, newItem);
            emitProgress("CONFLICT (file/directory): There is a directory with name " + parentPath
                    + " in " + mergeRevisionName + ". Adding " + parentPath + " as " + renamedPath);
        }
    }

    /**
     * 处理右侧（merge target）的一条变更：
     * - 若左侧未改动该路径，则直接加入 clean diff；
     * - 若左右都改动，尝试合并 mode/blob，并在必要时记录冲突。
     */
    private void processRightDiffEntry(
            DiffEntry right,
            Map<String, DiffEntry> leftByPath
    ) throws IOException {
        String path = PathUtils.normalizePath(right.getPath().toString());
        DiffEntry left = leftByPath.get(path);
        if (left == null) {
            // 左侧未修改该路径：右侧的 [base,right] 可直接应用
            cleanDiff.add(right);
            return;
        }

        // 左右都改动同一路径：尝试做三方合并，必要时记录冲突三元组。
        TreeEntry base = baseEntry(left, right);
        TreeEntry ours = postImage(left);
        TreeEntry theirs = postImage(right);

        if (TreeEntry.sameModeAndOid(ours, theirs)) {
            // 两侧结果一致，无需写入 clean diff
            return;
        }

        MergeResult<String> blobMerge = mergeBlobs(oidOf(base), oidOf(ours), oidOf(theirs));
        MergeResult<String> modeMerge = mergeModes(modeOf(base), modeOf(ours), modeOf(theirs));
        if (!blobMerge.clean || !modeMerge.clean) {
            conflicts.add(new Conflict(path, base, ours, theirs));
            emitProgress("Auto-merging " + path);
            emitProgress("CONFLICT (content): Merge conflict in " + path);
        }

        // 创建对应的cleanDiff，能兼容冲突的情况
        TreeEntry merged = toMergedEntry(path, modeMerge.value, blobMerge.value);
        DiffEntry mergedDiff = buildDiffAgainstLeft(path, ours, merged);
        if (mergedDiff != null) {
            cleanDiff.add(mergedDiff);
        }
    }

    private String oidOf(TreeEntry entry) {
        return entry != null ? entry.getOid() : null;
    }

    private String modeOf(TreeEntry entry) {
        return entry != null ? entry.getMode() : null;
    }

    private TreeEntry toMergedEntry(String path, String mergedMode, String mergedOid) {
        if (mergedMode == null || mergedOid == null) {
            return null;
        }
        String normalizedPath = PathUtils.normalizePath(path);
        String fileName = java.nio.file.Paths.get(normalizedPath).getFileName().toString();
        return new TreeEntry(mergedMode, fileName, mergedOid);
    }

    private Map<String, DiffEntry> toPathMap(List<DiffEntry> entries) {
        Map<String, DiffEntry> map = new HashMap<>();
        for (DiffEntry entry : entries) {
            map.put(PathUtils.normalizePath(entry.getPath().toString()), entry);
        }
        return map;
    }

    private TreeEntry baseEntry(DiffEntry left, DiffEntry right) {
        return left.getEntryA() != null ? left.getEntryA() : right.getEntryA();
    }

    private TreeEntry postImage(DiffEntry diff) {
        if (diff == null) {
            return null;
        }
        return diff.getEntryB();
    }

    /**
     * 生成一个可以针对当前工作区/暂存区（即 left 的 post-image）应用的 diff：
     * - leftPost 存在：使用 MODIFIED（或 DELETED）
     * - leftPost 不存在：使用 CREATED
     */
    private DiffEntry buildDiffAgainstLeft(String path, TreeEntry leftPost, TreeEntry merged) {
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        if (merged == null) {
            if (leftPost == null) {
                return null;
            }
            return new DiffEntry(DiffEntry.DiffStatus.DELETED, leftPost, null, p);
        }
        if (leftPost == null) {
            return new DiffEntry(DiffEntry.DiffStatus.CREATED, null, merged, p);
        }
        return new DiffEntry(DiffEntry.DiffStatus.MODIFIED, leftPost, merged, p);
    }

    /**
     * 三方值合并（仅用于字符串字段，如 oid/mode）。
     * 隐含前提：base/left/right 三者中最多只有一个为 null。
     */
    private MergeResult<String> merge3(String base, String left, String right) {
        // 两边同时改变，但是改变结果一样
        if (left != null && left.equals(right)) {
            return new MergeResult<>(left, true);
        }
        // 只有一边改变
        if (base != null && base.equals(left)) {
            return new MergeResult<>(right, true);
        }
        if (base != null && base.equals(right)) {
            return new MergeResult<>(left, true);
        }
        // 其余情况均为冲突情况
        return new MergeResult<>(null, false);
    }

    private MergeResult<String> mergeModes(String base, String left, String right) {
        MergeResult<String> r = merge3(base, left, right);
        if (r.value != null || r.clean) {
            return r;
        }
        // 不能合并 mode，选择 HEAD（left）但标记冲突
        return new MergeResult<>(left, false);
    }

    private MergeResult<String> mergeBlobs(String baseOid, String leftOid, String rightOid) throws IOException {
        MergeResult<String> r = merge3(baseOid, leftOid, rightOid);
        if (r.value != null || r.clean) {
            return r;
        }

        byte[] base = baseOid != null ? Repository.INSTANCE.getDatabase().loadBlob(baseOid).toBytes() : new byte[0];
        byte[] left = leftOid != null ? Repository.INSTANCE.getDatabase().loadBlob(leftOid).toBytes() : new byte[0];
        byte[] right = rightOid != null ? Repository.INSTANCE.getDatabase().loadBlob(rightOid).toBytes() : new byte[0];
        Diff3.MergeFileResult diff3 = Diff3.merge(
                new String(base, StandardCharsets.UTF_8),
                new String(left, StandardCharsets.UTF_8),
                new String(right, StandardCharsets.UTF_8)
        );
        byte[] merged = diff3.render(LEFT_NAME, mergeRevisionName).getBytes(StandardCharsets.UTF_8);
        String mergedOid = Repository.INSTANCE.getDatabase().store(new Blob(merged));
        return new MergeResult<>(mergedOid, !diff3.hasConflicts());
    }

    private void applyConflictsToIndex() throws IOException {
        if (conflicts.isEmpty()) {
            return;
        }
        Index index = Repository.INSTANCE.getIndex();
        index.load();
        for (Conflict c : conflicts) {
            index.addConflictSet(c.path, c.base, c.left, c.right);
        }
        index.save();
    }

    private void writeUntrackedFiles() throws IOException {
        for (Map.Entry<String, TreeEntry> e : untracked.entrySet()) {
            TreeEntry entry = e.getValue();
            byte[] content = Repository.INSTANCE.getDatabase().loadBlob(entry.getOid()).toBytes();
            Repository.INSTANCE.getWorkspace().writeFile(e.getKey(), content, entry.getMode());
        }
    }

    private void emitProgress(String message) {
        progressListener.accept(message);
    }

    private static final class Conflict {
        private final String path;
        private final TreeEntry base;
        private final TreeEntry left;
        private final TreeEntry right;

        private Conflict(String path, TreeEntry base, TreeEntry left, TreeEntry right) {
            this.path = path;
            this.base = base;
            this.left = left;
            this.right = right;
        }
    }

    private static final class MergeResult<T> {
        private final T value;
        private final boolean clean;

        private MergeResult(T value, boolean clean) {
            this.value = value;
            this.clean = clean;
        }
    }
}

