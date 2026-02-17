package com.weixiao.repo;

import com.weixiao.model.StatusResult;
import com.weixiao.obj.Blob;
import com.weixiao.obj.TreeEntry;
import com.weixiao.utils.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 仓库：定位 .git 目录，提供 ObjectDatabase、Refs、Workspace。
 * 代表整个git仓库的一个抽象
 */
public final class Repository {

    private static final Logger log = LoggerFactory.getLogger(Repository.class);

    private static final String GIT_DIR = ".git";

    private final Path root;   // 工作区根
    private final Path gitDir; // .git 目录
    private final ObjectDatabase database;
    private final Refs refs;
    private final Workspace workspace;
    private final Index index;

    /**
     * 以给定路径为仓库根（工作区根），.git 为 root/.git，并创建 ObjectDatabase、Refs、Workspace。
     */
    public Repository(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.gitDir = this.root.resolve(GIT_DIR);
        this.database = new ObjectDatabase(gitDir);
        this.refs = new Refs(gitDir);
        this.workspace = new Workspace(this.root);
        this.index = new Index(gitDir);
    }

    /**
     * 从当前目录向上查找包含 .git 的目录作为仓库根；未找到返回 null。
     */
    public static Repository find(Path start) {
        Path current = start.toAbsolutePath().normalize();
        Path root = Paths.get("/").normalize();
        log.debug("find repo start={}", current);
        while (current != null && !current.equals(root)) {
            if (java.nio.file.Files.exists(current.resolve(GIT_DIR))
                    && java.nio.file.Files.isDirectory(current.resolve(GIT_DIR))) {
                log.info("found repo at {}", current);
                return new Repository(current);
            }
            current = current.getParent();
        }
        log.debug("no repo found");
        return null;
    }

    /**
     * 工作区根目录（即仓库根）。
     */
    public Path getRoot() {
        return root;
    }

    /**
     * .git 目录路径。
     */
    public Path getGitDir() {
        return gitDir;
    }

    /**
     * 对象库，用于 store/load blob、tree、commit。
     */
    public ObjectDatabase getDatabase() {
        return database;
    }

    /**
     * 引用，用于 readHead、updateMaster。
     */
    public Refs getRefs() {
        return refs;
    }

    /**
     * 工作区，用于 listFiles、readFile。
     */
    public Workspace getWorkspace() {
        return workspace;
    }

    /**
     * 暂存区，用于 add/commit。
     */
    public Index getIndex() {
        return index;
    }

    /**
     * 计算 status：workspace vs index、index vs HEAD 的差异。
     * 调用前需已调用 {@link Index#load()}。
     *
     * @return 包含六类集合及 headPathToOid 的 StatusResult
     */
    public StatusResult getStatus() throws IOException {
        List<Index.Entry> indexEntries = new ArrayList<>(index.getEntries());
        Map<String, Index.Entry> pathToEntry = new LinkedHashMap<>();
        for (Index.Entry e : indexEntries) {
            pathToEntry.put(e.getPath(), e);
        }
        Set<String> trackedPaths = pathToEntry.keySet();

        List<WorkspaceFile> workspaceFiles = new ArrayList<>();
        collectWorkspaceFiles(root, "", trackedPaths, workspaceFiles);
        workspaceFiles.sort(Comparator.comparing(WorkspaceFile::getRelativePath));

        StatusResult result = new StatusResult();
        prepareHeadPathToOid(result);

        compareIndexToWorkspace(indexEntries, pathToEntry, workspaceFiles, result);
        compareIndexToHEAD(indexEntries, result.getHeadPathToOid(), result);

        return result;
    }

    private void prepareHeadPathToOid(StatusResult result) throws IOException {
        String headOid = refs.readHead();
        if (headOid == null) {
            return;
        }
        ObjectDatabase.RawObject commitRaw = database.load(headOid);
        if (!"commit".equals(commitRaw.getType())) {
            throw new IOException("HEAD is not a commit: " + headOid);
        }
        String treeOid = parseCommitTreeOid(commitRaw.getBody());
        if (treeOid == null) {
            throw new IOException("invalid commit format: " + headOid);
        }
        collectTreePathToOid(treeOid, "", result.getHeadPathToOid(), result.getHeadPathToMode());
    }

    private String parseCommitTreeOid(byte[] commitBody) {
        String content = new String(commitBody, java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.startsWith("tree ")) {
                return line.substring(5).trim();
            }
        }
        return null;
    }

    private void collectTreePathToOid(String treeOid, String prefix,
                                      Map<String, String> pathToOid, Map<String, String> pathToMode) throws IOException {
        ObjectDatabase.RawObject treeRaw = database.load(treeOid);
        if (!"tree".equals(treeRaw.getType())) {
            throw new IOException("expected tree, got " + treeRaw.getType() + ": " + treeOid);
        }
        List<TreeEntry> entries = parseTree(treeRaw.getBody());
        for (TreeEntry entry : entries) {
            String path = prefix.isEmpty() ? entry.getName() : prefix + "/" + entry.getName();
            if ("40000".equals(entry.getMode())) {
                collectTreePathToOid(entry.getOid(), path, pathToOid, pathToMode);
            } else {
                pathToOid.put(path, entry.getOid());
                pathToMode.put(path, entry.getMode());
            }
        }
    }

    private List<TreeEntry> parseTree(byte[] treeBody) throws IOException {
        List<TreeEntry> entries = new ArrayList<>();
        int pos = 0;
        while (pos < treeBody.length) {
            int nullPos = pos;
            while (nullPos < treeBody.length && treeBody[nullPos] != 0) {
                nullPos++;
            }
            if (nullPos >= treeBody.length) {
                throw new IOException("invalid tree format: missing null byte");
            }
            String header = new String(treeBody, pos, nullPos - pos, java.nio.charset.StandardCharsets.UTF_8);
            int spacePos = header.indexOf(' ');
            if (spacePos < 0) {
                throw new IOException("invalid tree entry header: " + header);
            }
            String mode = header.substring(0, spacePos);
            String name = header.substring(spacePos + 1);
            int oidStart = nullPos + 1;
            if (oidStart + 20 > treeBody.length) {
                throw new IOException("invalid tree format: oid out of bounds");
            }
            byte[] oidBytes = new byte[20];
            System.arraycopy(treeBody, oidStart, oidBytes, 0, 20);
            String oid = HexUtils.bytesToHex(oidBytes);
            entries.add(new TreeEntry(mode, name, oid));
            pos = oidStart + 20;
        }
        return entries;
    }

    private void collectWorkspaceFiles(Path dir, String prefix, Set<String> trackedPaths,
                                      List<WorkspaceFile> result) throws IOException {
        List<Path> entries = workspace.listEntries(dir);
        entries.sort(Comparator.comparing(a -> a.getFileName().toString()));

        for (Path child : entries) {
            String name = child.getFileName().toString();
            String relativePath = prefix.isEmpty() ? name : prefix + "/" + name;

            if (Files.isRegularFile(child)) {
                if (trackedPaths.contains(relativePath)) {
                    long fileSize = Files.size(child);
                    String mode = workspace.getFileMode(child);
                    Index.IndexStat stat = Workspace.getFileStat(child);
                    result.add(new WorkspaceFile(relativePath, child, false, fileSize, mode, stat));
                } else {
                    result.add(new WorkspaceFile(relativePath, child, false));
                }
            } else if (Files.isDirectory(child)) {
                if (!hasTrackedFilesUnder(relativePath, trackedPaths)) {
                    if (hasAnyFileUnder(workspace, child)) {
                        result.add(new WorkspaceFile(relativePath, child, true));
                    }
                } else {
                    collectWorkspaceFiles(child, relativePath, trackedPaths, result);
                }
            }
        }
    }

    private static boolean hasTrackedFilesUnder(String dirPath, Set<String> trackedPaths) {
        String prefix = dirPath.isEmpty() ? "" : dirPath + "/";
        return trackedPaths.stream()
                .anyMatch(p -> p.equals(dirPath) || p.startsWith(prefix));
    }

    private boolean hasAnyFileUnder(Workspace w, Path dir) throws IOException {
        for (Path child : w.listEntries(dir)) {
            if (Files.isRegularFile(child)) return true;
            if (Files.isDirectory(child) && hasAnyFileUnder(w, child)) return true;
        }
        return false;
    }

    private void compareIndexToWorkspace(List<Index.Entry> indexEntries,
                                         Map<String, Index.Entry> pathToEntry,
                                         List<WorkspaceFile> workspaceFiles,
                                         StatusResult result) throws IOException {
        int indexIdx = 0;
        int workspaceIdx = 0;

        while (indexIdx < indexEntries.size() || workspaceIdx < workspaceFiles.size()) {
            String indexPath = indexIdx < indexEntries.size() ? indexEntries.get(indexIdx).getPath() : null;
            String workspacePath = workspaceIdx < workspaceFiles.size() ? workspaceFiles.get(workspaceIdx).getRelativePath() : null;

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
                        Index.Entry entry = pathToEntry.get(indexPath);
                        if (isFileModified(workspace, wsFile.getAbsolutePath(), entry,
                                wsFile.getStat(), wsFile.getMode(), wsFile.getSize(), indexPath)) {
                            result.getWorkspaceModified().add(indexPath);
                        }
                    }
                    indexIdx++;
                    workspaceIdx++;
                }
            }
        }
    }

    private void compareIndexToHEAD(List<Index.Entry> indexEntries,
                                    Map<String, String> headPathToOid,
                                    StatusResult result) {
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

    private boolean isFileModified(Workspace w, Path filePath, Index.Entry entry,
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
        String workspaceOid = computeBlobOid(workspaceData);
        if (!workspaceOid.equals(entry.getOid())) {
            log.debug("modified: {} content changed: index={} workspace={}", relativePath, entry.getOid(), workspaceOid);
            return true;
        }
        return false;
    }

    public static String computeBlobOid(byte[] data) {
        Blob blob = new Blob(data);
        byte[] body = blob.toBytes();
        String header = blob.getType() + " " + body.length + "\0";
        byte[] headerBytes = header.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] content = new byte[headerBytes.length + body.length];
        System.arraycopy(headerBytes, 0, content, 0, headerBytes.length);
        System.arraycopy(body, 0, content, headerBytes.length, body.length);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(content);
            return HexUtils.bytesToHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
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

        String getRelativePath() { return relativePath; }
        Path getAbsolutePath() { return absolutePath; }
        boolean isDirectory() { return directory; }
        long getSize() { return fileSize != null ? fileSize : 0L; }
        String getMode() { return mode; }
        Index.IndexStat getStat() { return stat; }
    }
}
