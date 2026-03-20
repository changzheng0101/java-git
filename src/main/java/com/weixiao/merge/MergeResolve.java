package com.weixiao.merge;

import com.weixiao.diff.DiffEntry;
import com.weixiao.diff.TreeDiff;
import com.weixiao.obj.Blob;
import com.weixiao.obj.TreeEntry;
import com.weixiao.repo.Index;
import com.weixiao.repo.Migration;
import com.weixiao.repo.Repository;
import com.weixiao.utils.PathUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merge 解析与执行：基于 Inputs，把 base->merge 的净变化应用到 workspace 与 index。
 */
public final class MergeResolve {

    private final MergeInputs inputs;
    private final String mergeRevisionName;
    /**
     * 可以直接应用在Workspace和Index上的修改，会包含冲突的文件，但是DiffEntry中的entryB是处理冲突后的结果
     */
    private final List<DiffEntry> cleanDiff = new ArrayList<>();
    private final List<Conflict> conflicts = new ArrayList<>();

    public MergeResolve(MergeInputs inputs, String mergeRevisionName) {
        this.inputs = inputs;
        this.mergeRevisionName = mergeRevisionName;
    }

    public void execute() throws IOException {
        prepareTreeDiffs();

        Migration migration = new Migration(cleanDiff);
        migration.applyChanges();

        applyConflictsToIndex();
    }

    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }

    private void prepareTreeDiffs() throws IOException {
        List<DiffEntry> leftDiff = TreeDiff.diff(inputs.getBaseOid(), inputs.getHeadOid(), java.nio.file.Paths.get(""));
        List<DiffEntry> rightDiff = TreeDiff.diff(inputs.getBaseOid(), inputs.getMergeOid(), java.nio.file.Paths.get(""));

        Map<String, DiffEntry> leftByPath = toPathMap(leftDiff);
        cleanDiff.clear();
        conflicts.clear();

        for (DiffEntry right : rightDiff) {
            processRightDiffEntry(right, leftByPath);
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
        }


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

        byte[] left = leftOid != null ? Repository.INSTANCE.getDatabase().loadBlob(leftOid).toBytes() : new byte[0];
        byte[] right = rightOid != null ? Repository.INSTANCE.getDatabase().loadBlob(rightOid).toBytes() : new byte[0];
        byte[] merged = buildConflictBlob(left, right);
        String mergedOid = Repository.INSTANCE.getDatabase().store(new Blob(merged));
        return new MergeResult<>(mergedOid, false);
    }

    private byte[] buildConflictBlob(byte[] left, byte[] right) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("<<<<<<< HEAD\n".getBytes(StandardCharsets.UTF_8));
        out.write(left);
        if (left.length == 0 || left[left.length - 1] != '\n') {
            out.write('\n');
        }
        out.write("=======\n".getBytes(StandardCharsets.UTF_8));
        out.write(right);
        if (right.length == 0 || right[right.length - 1] != '\n') {
            out.write('\n');
        }
        out.write((">>>>>>> " + mergeRevisionName + "\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
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

