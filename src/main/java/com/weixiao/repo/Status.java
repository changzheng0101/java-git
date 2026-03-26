package com.weixiao.repo;

import com.weixiao.model.FileStatus;
import com.weixiao.model.StatusResult;
import com.weixiao.obj.TreeEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计算工作区状态：workspace vs index、index vs HEAD tree。
 * 提供单文件判定方法供 getStatus 复用。
 */
public final class Status {

    private static final Logger log = LoggerFactory.getLogger(Status.class);

    /**
     * 计算 status：workspace vs index、index vs HEAD 的差异。调用前需已调用 {@link Index#load()}。
     * 使用当前 {@link Repository#INSTANCE}。
     */
    public static StatusResult getStatus() throws IOException {
        Repository repo = Repository.INSTANCE;
        StatusResult result = new StatusResult();
        Map<String, TreeEntry> headPathToEntry = new LinkedHashMap<>();
        String headOid = repo.getRefs().readHead();
        if (headOid != null) {
            repo.collectCommitTreeTo(headOid, headPathToEntry);
        }

        List<Index.Entry> allIndexEntries = new ArrayList<>(repo.getIndex().getEntries());
        Set<String> stage0Paths = checkIndexEntries(allIndexEntries, headPathToEntry, result);

        // 检查 index delete文件， head中有 但是index删除的文件
        collectIndexDeleted(stage0Paths, headPathToEntry.keySet(), result);

        // 检查 untracked file，head中没有，但是Workspace有
        Set<String> trackedPaths = new java.util.HashSet<>(stage0Paths);
        trackedPaths.addAll(result.getConflicts().keySet());
        collectWorkspaceUntracked(repo.getRoot(), "", trackedPaths, result.getWorkspaceUntracked());

        return result;
    }

    /**
     * 遍历 index 所有条目：
     * - stage=0：检查 index 与 workspace、index 与 HEAD 的差异；
     * - stage!=0：记录冲突路径及其 stage 集合。
     *
     * @return stage=0 的路径集合
     */
    private static Set<String> checkIndexEntries(List<Index.Entry> allIndexEntries,
                                                 Map<String, TreeEntry> headPathToEntry,
                                                 StatusResult result) throws IOException {
        Set<String> stage0Paths = new java.util.HashSet<>();
        for (Index.Entry entry : allIndexEntries) {
            if (entry.getStage() == 0) {
                stage0Paths.add(entry.getPath());
                checkStage0Entry(entry, headPathToEntry, result);
            } else {
                result.getConflicts()
                        .computeIfAbsent(entry.getPath(), k -> new java.util.TreeSet<>())
                        .add(entry.getStage());
            }
        }
        return stage0Paths;
    }

    /**
     * 检查一条 stage-0 index 记录并更新 status 结果：
     * - workspace 侧：MODIFIED / DELETED
     * - HEAD 侧：ADDED / MODIFIED
     */
    private static void checkStage0Entry(Index.Entry entry,
                                         Map<String, TreeEntry> headPathToEntry,
                                         StatusResult result) throws IOException {
        FileStatus workspaceStatus = checkIndexAgainstWorkspace(entry);
        if (workspaceStatus == FileStatus.MODIFIED) {
            result.getWorkspaceModified().add(entry.getPath());
        } else if (workspaceStatus == FileStatus.DELETED) {
            result.getWorkspaceDeleted().add(entry.getPath());
        }

        FileStatus headStatus = checkIndexAgainstHeadTree(entry, headPathToEntry);
        if (headStatus == FileStatus.ADDED) {
            result.getIndexAdded().add(entry.getPath());
        } else if (headStatus == FileStatus.MODIFIED) {
            result.getIndexModified().add(entry.getPath());
        }
    }

    /**
     * 计算单条 index 记录（stage-0）相对 workspace 的状态。
     */
    private static FileStatus checkIndexAgainstWorkspace(Index.Entry indexEntry) throws IOException {
        Repository repo = Repository.INSTANCE;
        String path = indexEntry.getPath();
        Path filePath = repo.getRoot().resolve(path.replace('\\', '/'));
        boolean existsInWorkspace = Files.exists(filePath) && Files.isRegularFile(filePath);
        if (!existsInWorkspace) {
            return FileStatus.DELETED;
        }
        long fileSize = Files.size(filePath);
        String workspaceMode = repo.getWorkspace().getFileMode(filePath);
        Index.IndexStat workspaceStat = Workspace.getFileStat(filePath);
        if (isFileModified(repo.getWorkspace(), filePath, indexEntry, workspaceStat, workspaceMode, fileSize, path)) {
            return FileStatus.MODIFIED;
        }
        return FileStatus.UNCHANGED;
    }

    /**
     * 计算单条 index 记录（stage-0）相对 HEAD tree 的状态。
     */
    private static FileStatus checkIndexAgainstHeadTree(Index.Entry indexEntry,
                                                        Map<String, TreeEntry> headPathToEntry) {
        String path = indexEntry.getPath();
        TreeEntry headEntry = headPathToEntry.get(path);
        if (headEntry == null) {
            return FileStatus.ADDED;
        }
        boolean oidChanged = !indexEntry.getOid().equals(headEntry.getOid());
        boolean modeChanged = !indexEntry.getMode().equals(headEntry.getMode());
        return (oidChanged || modeChanged) ? FileStatus.MODIFIED : FileStatus.UNCHANGED;
    }

    /**
     * 统计 index vs HEAD 中的删除：HEAD 有、stage-0 index 无。
     */
    private static void collectIndexDeleted(Set<String> stage0Paths,
                                            Set<String> headPaths,
                                            StatusResult result) {
        for (String headPath : headPaths) {
            if (!stage0Paths.contains(headPath)) {
                result.getIndexDeleted().add(headPath);
            }
        }
    }

    /**
     * 递归收集 workspace 中的未跟踪路径：
     * - 文件：直接记录 path
     * - 目录：若目录下无 tracked 文件且包含任意文件，记录 "dir/"；否则继续递归
     */
    private static void collectWorkspaceUntracked(Path dir, String prefix,
                                                  Set<String> trackedPaths,
                                                  Set<String> workspaceUntracked) throws IOException {
        Repository repo = Repository.INSTANCE;
        List<Path> entries = repo.getWorkspace().listEntries(dir);
        entries.sort(Comparator.comparing(a -> a.getFileName().toString()));

        for (Path child : entries) {
            String name = child.getFileName().toString();
            String relativePath = prefix.isEmpty() ? name : prefix + "/" + name;

            if (Files.isRegularFile(child)) {
                if (!trackedPaths.contains(relativePath)) {
                    workspaceUntracked.add(relativePath);
                }
            } else if (Files.isDirectory(child)) {
                if (!hasTrackedFilesUnder(relativePath, trackedPaths)) {
                    if (hasAnyFileUnder(repo.getWorkspace(), child)) {
                        workspaceUntracked.add(relativePath + "/");
                    }
                } else {
                    collectWorkspaceUntracked(child, relativePath, trackedPaths, workspaceUntracked);
                }
            }
        }
    }

    /**
     * 判断 trackedPaths 中是否存在 dirPath 或其子路径。
     */
    private static boolean hasTrackedFilesUnder(String dirPath, Set<String> trackedPaths) {
        String prefix = dirPath.isEmpty() ? "" : dirPath + "/";
        return trackedPaths.stream()
                .anyMatch(p -> p.equals(dirPath) || p.startsWith(prefix));
    }

    /**
     * 判断目录下是否存在任意文件（递归），用于未跟踪目录聚合显示。
     */
    private static boolean hasAnyFileUnder(Workspace w, Path dir) throws IOException {
        for (Path child : w.listEntries(dir)) {
            if (Files.isRegularFile(child)) {
                return true;
            }
            if (Files.isDirectory(child) && hasAnyFileUnder(w, child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 基于 size/mode/stat/content 判断 workspace 文件是否相对 index 条目发生变化。
     */
    private static boolean isFileModified(Workspace w, Path filePath, Index.Entry entry,
                                          Index.IndexStat workspaceStat, String workspaceMode,
                                          long fileSize, String relativePath) throws IOException {
        if (fileSize != entry.getSize()) {
            log.debug("modified: {} size changed: {} -> {}", relativePath, entry.getSize(), fileSize);
            return true;
        }
        if (!workspaceMode.equals(entry.getMode())) {
            log.debug("modified: {} mode changed: {} -> {}", relativePath, entry.getMode(), workspaceMode);
            return true;
        }
        if (entry.getStat() != null && workspaceStat != null) {
            Index.IndexStat indexStat = entry.getStat();
            if (workspaceStat.getCtimeSec() == indexStat.getCtimeSec()
                    && workspaceStat.getCtimeNsec() == indexStat.getCtimeNsec()
                    && workspaceStat.getMtimeSec() == indexStat.getMtimeSec()
                    && workspaceStat.getMtimeNsec() == indexStat.getMtimeNsec()) {
                log.debug("unchanged: {} ctime/mtime match, skipping content check", relativePath);
                return false;
            }
        }
        byte[] workspaceData = w.readFile(filePath);
        String workspaceOid = Repository.computeBlobOid(workspaceData);
        if (!workspaceOid.equals(entry.getOid())) {
            log.debug("modified: {} content changed: index={} workspace={}", relativePath, entry.getOid(), workspaceOid);
            return true;
        }
        return false;
    }

}
