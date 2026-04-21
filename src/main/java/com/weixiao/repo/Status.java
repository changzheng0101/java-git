package com.weixiao.repo;

import com.weixiao.model.FileStatus;
import com.weixiao.model.StatusResult;
import com.weixiao.obj.TreeEntry;
import com.weixiao.utils.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计算工作区状态：workspace vs index、index vs HEAD tree。
 * <p>
 * 主流程与 git status 一致：
 * 1) 扫描 workspace（tracked 快照 + untracked）；
 * 2) 遍历 index（stage-0 做双向比对，非 0 记录冲突）；
 * 3) 从 HEAD tree 反查 staged deleted（HEAD 有、index 无）。
 * <p>
 */
public final class Status {

    private static final Logger log = LoggerFactory.getLogger(Status.class);

    private final Repository repo;
    private final StatusResult result;
    /**
     * HEAD 对应的缓存  path -> TreeEntry
     */
    private final Map<String, TreeEntry> headPathToEntry;
    /**
     * 记录Workspace缓存状态  path -> WorkspaceSnapshot
     */
    private final Map<String, WorkspaceFileMetaData> workspaceFileMetaDataCache;

    /**
     * 工作区单文件元数据保存
     */
    private record WorkspaceFileMetaData(Path filePath, long fileSize, String mode, Index.IndexStat stat) {
    }

    /**
     * 构造一次 status 计算上下文：读取 HEAD tree 快照到 headPathToEntry。
     */
    private Status() throws IOException {
        this.repo = Repository.INSTANCE;
        this.result = new StatusResult();
        this.headPathToEntry = new LinkedHashMap<>();
        this.workspaceFileMetaDataCache = new HashMap<>();
        repo.getIndex().load();

        String headOid = repo.getRefs().readHead();
        if (headOid != null) {
            repo.collectCommitTreeTo(headOid, headPathToEntry);
        }
    }

    /**
     * 计算 status：workspace vs index、index vs HEAD 的差异。调用前需已调用 {@link Index#load()}。
     * 使用当前 {@link Repository#INSTANCE}。
     */
    public static StatusResult getStatus() throws IOException {
        return new Status().collect();
    }

    private StatusResult collect() throws IOException {
        // 1) 扫描 workspace（收集 tracked 快照 + untracked）
        scanWorkspace(Path.of(""));
        // 2) 比较 index vs workspace 与 index vs HEAD
        checkIndexEntries();
        // 3) 补齐 index deleted（HEAD 有、index 无）
        collectDeletedHeadFiles();
        return result;
    }

    /**
     * 遍历 index 所有条目：
     * - stage=0：检查 index 与 workspace、index 与 HEAD 的差异；
     * - stage!=0：记录冲突路径及其 stage 集合。
     *
     */
    private void checkIndexEntries() throws IOException {
        List<Index.Entry> indexEntries = repo.getIndex().getEntries();
        for (Index.Entry entry : indexEntries) {
            if (entry.getStage() != 0) {
                result.getConflicts()
                        .computeIfAbsent(entry.getPath(), k -> new java.util.TreeSet<>())
                        .add(entry.getStage());
            } else {
                checkNonConflictEntry(entry);
            }
        }
    }

    /**
     * 检查一条 stage-0 index 记录并更新 status 结果：
     * - workspace 侧：MODIFIED / DELETED
     * - HEAD 侧：ADDED / MODIFIED
     */
    private void checkNonConflictEntry(Index.Entry entry) throws IOException {
        FileStatus workspaceStatus = checkIndexAgainstWorkspace(entry);
        if (workspaceStatus == FileStatus.MODIFIED) {
            result.getWorkspaceModified().add(entry.getPath());
        } else if (workspaceStatus == FileStatus.DELETED) {
            result.getWorkspaceDeleted().add(entry.getPath());
        }

        FileStatus headStatus = checkIndexAgainstHeadTree(entry);
        if (headStatus == FileStatus.ADDED) {
            result.getIndexAdded().add(entry.getPath());
        } else if (headStatus == FileStatus.MODIFIED) {
            result.getIndexModified().add(entry.getPath());
        }
    }

    /**
     * 计算单条 index 记录（stage-0）相对 workspace 的状态。
     */
    private FileStatus checkIndexAgainstWorkspace(Index.Entry indexEntry) throws IOException {
        String path = indexEntry.getPath();
        WorkspaceFileMetaData workspaceFileMetaData = workspaceFileMetaDataCache.get(path);
        if (workspaceFileMetaData == null) {
            return FileStatus.DELETED;
        }
        if (isFileModified(
                workspaceFileMetaData.filePath,
                indexEntry,
                workspaceFileMetaData.stat,
                workspaceFileMetaData.mode,
                workspaceFileMetaData.fileSize,
                path
        )) {
            return FileStatus.MODIFIED;
        }
        return FileStatus.UNCHANGED;
    }

    /**
     * 计算单条 index 记录（stage-0）相对 HEAD tree 的状态。
     */
    private FileStatus checkIndexAgainstHeadTree(Index.Entry indexEntry) {
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
     * 统计 index vs HEAD 中的删除：HEAD 有、index未追踪
     */
    private void collectDeletedHeadFiles() {
        for (String headPath : headPathToEntry.keySet()) {
            if (!repo.getIndex().tracked(headPath)) {
                result.getIndexDeleted().add(headPath);
            }
        }
    }

    /**
     *
     * @param basePath 相对于git根目录的目录 例如 a/b
     */
    private void scanWorkspace(Path basePath) throws IOException {
        List<Path> subPaths = Workspace.listEntries(repo.getRoot().resolve(basePath));

        for (Path subPath : subPaths) {
            //  相对路径，类似  a/b
            Path relativePath = repo.getRoot().relativize(subPath);
            String normalizedPath = PathUtils.normalizePath(relativePath);
            if (repo.getIndex().tracked(normalizedPath)) {
                if (Files.isRegularFile(subPath)) {
                    workspaceFileMetaDataCache.put(
                            normalizedPath,
                            new WorkspaceFileMetaData(subPath, Files.size(subPath), Workspace.getFileMode(subPath), Workspace.getFileStat(subPath))
                    );
                }
                if (Files.isDirectory(subPath)) {
                    scanWorkspace(relativePath);
                }
            } else {
                if (Files.isDirectory(subPath) && PathUtils.containsOnlyEmptyDirectories(subPath)) {
                    continue;
                }
                result.getWorkspaceUntracked().add(Files.isDirectory(subPath) ? normalizedPath + "/" : normalizedPath);
            }
        }
    }

    /**
     * 基于 size/mode/stat/content 判断 workspace 文件是否相对 index 条目发生变化。
     */
    private boolean isFileModified(Path filePath, Index.Entry entry, Index.IndexStat workspaceStat, String workspaceMode,
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
        byte[] workspaceData = repo.getWorkspace().readFile(filePath);
        String workspaceOid = Repository.computeBlobOid(workspaceData);
        if (!workspaceOid.equals(entry.getOid())) {
            log.debug("modified: {} content changed: index={} workspace={}", relativePath, entry.getOid(), workspaceOid);
            return true;
        }
        return false;
    }

}
