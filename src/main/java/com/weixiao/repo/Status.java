package com.weixiao.repo;

import com.weixiao.model.FileStatus;
import com.weixiao.model.StatusResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
        List<Index.Entry> indexEntries = new ArrayList<>(repo.getIndex().getEntries());
        Map<String, Index.Entry> pathToEntry = new LinkedHashMap<>();
        for (Index.Entry e : indexEntries) {
            pathToEntry.put(e.getPath(), e);
        }
        Set<String> trackedPaths = pathToEntry.keySet();

        List<WorkspaceFile> workspaceFiles = collectWorkspaceFiles(repo.getRoot(), "", trackedPaths);
        workspaceFiles.sort(Comparator.comparing(WorkspaceFile::getRelativePath));

        StatusResult result = new StatusResult();
        prepareHeadPathToOid(result);

        compareIndexToWorkspace(indexEntries, pathToEntry, workspaceFiles, result);
        compareIndexToHEAD(indexEntries, result.getHeadPathToOid(), result);

        return result;
    }

    /**
     * 仅判断单文件：index 与 workspace 的差异。使用当前 {@link Repository#INSTANCE}。
     *
     * @return MODIFIED / ADDED / DELETED / UNCHANGED
     */
    public static FileStatus check_index_against_workspace(String path) throws IOException {
        Repository repo = Repository.INSTANCE;
        Index.Entry indexEntry = repo.getIndex().getEntryForPath(path);
        Path filePath = repo.getRoot().resolve(path.replace('\\', '/'));
        boolean existsInWorkspace = Files.exists(filePath) && Files.isRegularFile(filePath);

        if (indexEntry == null) {
            return existsInWorkspace ? FileStatus.ADDED : FileStatus.UNCHANGED;
        }
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
     * 仅判断单文件：index 与 HEAD tree 的差异。使用当前 {@link Repository#INSTANCE}。
     *
     * @return MODIFIED / ADDED / DELETED / UNCHANGED
     */
    public static FileStatus check_index_against_head_tree(String path) throws IOException {
        Repository repo = Repository.INSTANCE;
        Map<String, String> headPathToOid = new LinkedHashMap<>();
        Map<String, String> headPathToMode = new LinkedHashMap<>();
        String headOid = repo.getRefs().readHead();
        if (headOid != null) {
            repo.collectCommitTreeTo(headOid, headPathToOid, headPathToMode);
        }

        Index.Entry indexEntry = repo.getIndex().getEntryForPath(path);
        boolean inHead = headPathToOid.containsKey(path);

        if (indexEntry == null) {
            return inHead ? FileStatus.DELETED : FileStatus.UNCHANGED;
        }
        if (!inHead) {
            return FileStatus.ADDED;
        }
        String indexOid = indexEntry.getOid();
        String headOidForPath = headPathToOid.get(path);
        String headMode = headPathToMode.get(path);
        String indexMode = indexEntry.getMode();
        boolean oidChanged = headOidForPath != null && !indexOid.equals(headOidForPath);
        boolean modeChanged = headMode != null && !headMode.equals(indexMode);
        return (oidChanged || modeChanged) ? FileStatus.MODIFIED : FileStatus.UNCHANGED;
    }

    private static void prepareHeadPathToOid(StatusResult result) throws IOException {
        Repository repo = Repository.INSTANCE;
        String headOid = repo.getRefs().readHead();
        if (headOid == null) {
            return;
        }
        repo.collectCommitTreeTo(headOid, result.getHeadPathToOid(), result.getHeadPathToMode());
    }

    private static List<WorkspaceFile> collectWorkspaceFiles(Path dir, String prefix,
                                                            Set<String> trackedPaths) throws IOException {
        Repository repo = Repository.INSTANCE;
        List<WorkspaceFile> result = new ArrayList<>();
        List<Path> entries = repo.getWorkspace().listEntries(dir);
        entries.sort(Comparator.comparing(a -> a.getFileName().toString()));

        for (Path child : entries) {
            String name = child.getFileName().toString();
            String relativePath = prefix.isEmpty() ? name : prefix + "/" + name;

            if (Files.isRegularFile(child)) {
                if (trackedPaths.contains(relativePath)) {
                    long fileSize = Files.size(child);
                    String mode = repo.getWorkspace().getFileMode(child);
                    Index.IndexStat stat = Workspace.getFileStat(child);
                    result.add(new WorkspaceFile(relativePath, child, false, fileSize, mode, stat));
                } else {
                    result.add(new WorkspaceFile(relativePath, child, false));
                }
            } else if (Files.isDirectory(child)) {
                if (!hasTrackedFilesUnder(relativePath, trackedPaths)) {
                    if (hasAnyFileUnder(repo.getWorkspace(), child)) {
                        result.add(new WorkspaceFile(relativePath, child, true));
                    }
                } else {
                    result.addAll(collectWorkspaceFiles(child, relativePath, trackedPaths));
                }
            }
        }
        return result;
    }

    private static boolean hasTrackedFilesUnder(String dirPath, Set<String> trackedPaths) {
        String prefix = dirPath.isEmpty() ? "" : dirPath + "/";
        return trackedPaths.stream()
                .anyMatch(p -> p.equals(dirPath) || p.startsWith(prefix));
    }

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

    private static void compareIndexToWorkspace(List<Index.Entry> indexEntries,
                                                Map<String, Index.Entry> pathToEntry,
                                                List<WorkspaceFile> workspaceFiles,
                                                StatusResult result) throws IOException {
        int indexIdx = 0;
        int workspaceIdx = 0;

        while (indexIdx < indexEntries.size() || workspaceIdx < workspaceFiles.size()) {
            String indexPath = indexIdx < indexEntries.size() ? indexEntries.get(indexIdx).getPath() : null;
            String workspacePath = workspaceIdx < workspaceFiles.size()
                    ? workspaceFiles.get(workspaceIdx).getRelativePath() : null;

            if (workspacePath == null) {
                result.getWorkspaceDeleted().add(indexPath);
                log.debug("workspace deleted: {}", indexPath);
                indexIdx++;
            } else if (indexPath == null) {
                WorkspaceFile wsFile = workspaceFiles.get(workspaceIdx);
                if (wsFile.isDirectory()) {
                    result.getWorkspaceUntracked().add(workspacePath + "/");
                } else {
                    result.getWorkspaceUntracked().add(workspacePath);
                }
                workspaceIdx++;
            } else {
                int cmp = workspacePath.compareTo(indexPath);
                if (cmp < 0) {
                    WorkspaceFile wsFile = workspaceFiles.get(workspaceIdx);
                    if (wsFile.isDirectory()) {
                        result.getWorkspaceUntracked().add(workspacePath + "/");
                    } else {
                        result.getWorkspaceUntracked().add(workspacePath);
                    }
                    workspaceIdx++;
                } else if (cmp > 0) {
                    result.getWorkspaceDeleted().add(indexPath);
                    log.debug("workspace deleted: {}", indexPath);
                    indexIdx++;
                } else {
                    WorkspaceFile wsFile = workspaceFiles.get(workspaceIdx);
                    if (!wsFile.isDirectory()) {
                        FileStatus status = check_index_against_workspace(indexPath);
                        if (status == FileStatus.MODIFIED) {
                            result.getWorkspaceModified().add(indexPath);
                        }
                    }
                    indexIdx++;
                    workspaceIdx++;
                }
            }
        }
    }

    private static void compareIndexToHEAD(List<Index.Entry> indexEntries,
                                           Map<String, String> headPathToOid,
                                           StatusResult result) throws IOException {
        List<String> headPaths = new ArrayList<>(headPathToOid.keySet());
        Collections.sort(headPaths);

        if (headPaths.isEmpty()) {
            for (Index.Entry entry : indexEntries) {
                result.getIndexAdded().add(entry.getPath());
            }
            return;
        }

        int indexIdx = 0;
        int headIdx = 0;

        while (indexIdx < indexEntries.size() || headIdx < headPaths.size()) {
            String indexPath = indexIdx < indexEntries.size() ? indexEntries.get(indexIdx).getPath() : null;
            String headPath = headIdx < headPaths.size() ? headPaths.get(headIdx) : null;

            if (indexPath == null) {
                result.getIndexDeleted().add(headPath);
                headIdx++;
            } else if (headPath == null) {
                result.getIndexAdded().add(indexPath);
                indexIdx++;
            } else {
                int cmp = indexPath.compareTo(headPath);
                if (cmp < 0) {
                    result.getIndexAdded().add(indexPath);
                    indexIdx++;
                } else if (cmp > 0) {
                    result.getIndexDeleted().add(headPath);
                    headIdx++;
                } else {
                    Index.Entry indexEntry = indexEntries.get(indexIdx);
                    String indexOid = indexEntry.getOid();
                    String headOid = headPathToOid.get(headPath);
                    String headMode = result.getHeadPathToMode().get(headPath);
                    String indexMode = indexEntry.getMode();
                    boolean oidChanged = headOid != null && !indexOid.equals(headOid);
                    boolean modeChanged = headMode != null && !headMode.equals(indexMode);
                    if (oidChanged || modeChanged) {
                        result.getIndexModified().add(indexPath);
                    }
                    indexIdx++;
                    headIdx++;
                }
            }
        }
    }

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

    private static final class WorkspaceFile {
        private final String relativePath;
        private final Path absolutePath;
        private final boolean directory;
        private final Long fileSize;
        private final String mode;
        private final Index.IndexStat stat;

        WorkspaceFile(String relativePath, Path absolutePath, boolean directory) {
            this(relativePath, absolutePath, directory, null, null, null);
        }

        WorkspaceFile(String relativePath, Path absolutePath, boolean directory,
                      Long fileSize, String mode, Index.IndexStat stat) {
            this.relativePath = relativePath;
            this.absolutePath = absolutePath;
            this.directory = directory;
            this.fileSize = fileSize;
            this.mode = mode;
            this.stat = stat;
        }

        String getRelativePath() {
            return relativePath;
        }

        Path getAbsolutePath() {
            return absolutePath;
        }

        boolean isDirectory() {
            return directory;
        }

        long getSize() {
            return fileSize != null ? fileSize : 0L;
        }

        String getMode() {
            return mode;
        }

        Index.IndexStat getStat() {
            return stat;
        }
    }
}
