package com.weixiao.command;

import com.weixiao.obj.Blob;
import com.weixiao.repo.Repository;
import com.weixiao.repo.Workspace;
import com.weixiao.utils.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * jit status - 显示工作区状态，当前支持列出未跟踪文件（untracked files）。
 */
@Command(name = "status", mixinStandardHelpOptions = true, description = "显示工作区状态（含未跟踪文件）")
public class StatusCommand implements Runnable, IExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(StatusCommand.class);

    @Parameters(index = "0", arity = "0..1", description = "仓库根路径，默认为当前目录")
    private Path path;

    @Option(names = {"--porcelain"}, description = "以机器可读格式输出（每行一个文件，格式：?? <path> 或  M <path>）")
    private boolean porcelain;

    private int exitCode = 0;

    @Override
    public void run() {
        exitCode = 0;
        Path start = path != null ? path.toAbsolutePath().normalize() : Paths.get("").toAbsolutePath().normalize();
        log.debug("status start path={}", start);

        Repository repo = Repository.find(start);
        if (repo == null) {
            log.debug("no repo found from {}", start);
            System.err.println("fatal: not a jit repository (or any of the parent directories): .git");
            exitCode = 1;
            return;
        }

        try {
            repo.getIndex().load();
            Path root = repo.getRoot();
            StatusResult result = collectStatus(repo, root);
            List<String> modified = result.getModified();
            List<String> deleted = result.getDeleted();
            List<String> untracked = result.getUntracked();

            if (porcelain) {
                // 机器可读格式：Modified 为 " M <path>"，Deleted 为 " D <path>"，Untracked 为 "?? <path>"
                for (String p : modified.stream().sorted().collect(Collectors.toList())) {
                    System.out.println(" M " + p);
                }
                for (String p : deleted.stream().sorted().collect(Collectors.toList())) {
                    System.out.println(" D " + p);
                }
                for (String p : untracked.stream().sorted().collect(Collectors.toList())) {
                    System.out.println("?? " + p);
                }
            } else {
                // 人类可读格式
                boolean hasChanges = false;
                if (!modified.isEmpty() || !deleted.isEmpty()) {
                    System.out.println("Changes not staged for commit:");
                    for (String p : modified.stream().sorted().collect(Collectors.toList())) {
                        System.out.println("  modified:   " + p);
                    }
                    for (String p : deleted.stream().sorted().collect(Collectors.toList())) {
                        System.out.println("  deleted:    " + p);
                    }
                    hasChanges = true;
                }
                if (!untracked.isEmpty()) {
                    System.out.println("Untracked files:");
                    for (String p : untracked.stream().sorted().collect(Collectors.toList())) {
                        System.out.println("  " + p);
                    }
                    hasChanges = true;
                }
                if (!hasChanges) {
                    System.out.println("nothing to commit, working tree clean");
                }
            }
        } catch (IOException e) {
            log.error("status failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }

    /**
     * 判断工作区文件是否与 index 中的记录不一致（Modified）。
     * 采用分层检测策略以提高性能，使用缓存的 stat 信息避免重复文件系统访问。
     * 1. 先比较文件大小（size），不同则一定 Modified
     * 2. 再比较文件权限（mode），不同则一定 Modified
     * 3. 比较时间戳（ctime/mtime），如果都相同则认为是同一文件，无需读取内容
     * 4. 最后比较文件内容（oid），只有前面都相同时才计算 oid
     *
     * @param workspace     工作区，用于读取文件内容
     * @param filePath      工作区文件的绝对路径
     * @param entry         index 中对应的 Entry（包含 size、mode、oid、stat）
     * @param workspaceStat 工作区文件的 stat 信息（已缓存，避免重复读取）
     * @param workspaceMode 工作区文件的 mode（已缓存）
     * @param fileSize      工作区文件的大小（已缓存）
     * @param relativePath  文件的相对路径（用于日志）
     * @return true 表示文件已修改，false 表示文件未修改
     */
    private boolean isFileModified(Workspace workspace, Path filePath,
                                   com.weixiao.repo.Index.Entry entry,
                                   com.weixiao.repo.Index.IndexStat workspaceStat,
                                   String workspaceMode,
                                   long fileSize,
                                   String relativePath) throws IOException {
        // 1. 比较文件大小
        if (fileSize != entry.getSize()) {
            log.debug("modified: {} size changed: {} -> {}", relativePath, entry.getSize(), fileSize);
            return true;
        }

        // 2. 比较文件权限（mode）
        if (!workspaceMode.equals(entry.getMode())) {
            log.debug("modified: {} mode changed: {} -> {}", relativePath, entry.getMode(), workspaceMode);
            return true;
        }

        // 3. 比较时间戳（ctime 和 mtime），如果都相同则认为是同一文件，无需读取内容计算 hash
        if (entry.getStat() != null && workspaceStat != null) {
            com.weixiao.repo.Index.IndexStat indexStat = entry.getStat();
            if (workspaceStat.getCtimeSec() == indexStat.getCtimeSec()
                    && workspaceStat.getCtimeNsec() == indexStat.getCtimeNsec()
                    && workspaceStat.getMtimeSec() == indexStat.getMtimeSec()
                    && workspaceStat.getMtimeNsec() == indexStat.getMtimeNsec()) {
                // ctime 和 mtime 都相同，认为是同一文件，未修改
                log.debug("unchanged: {} ctime/mtime match, skipping content check", relativePath);
                return false;
            }
        }

        // 4. size 和 mode 相同，但时间戳不同（或 stat 为 null），比较文件内容（oid）
        byte[] workspaceData = workspace.readFile(filePath);
        String workspaceOid = computeBlobOid(workspaceData);
        if (!workspaceOid.equals(entry.getOid())) {
            log.debug("modified: {} content changed: index={} workspace={}", relativePath, entry.getOid(), workspaceOid);
            return true;
        }

        return false;
    }

    /**
     * 计算工作区文件的 blob oid（不写入对象库），用于与 index 中的 oid 比较。
     */
    private String computeBlobOid(byte[] data) {
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

    /**
     * 使用双指针同步遍历方式收集 Modified、Deleted 和 Untracked。
     * 利用 index 和工作区路径的有序性，一次遍历同时处理所有状态。
     * 参考 Git 实现：同时遍历排序后的 index entries 和工作区文件列表，通过路径比较确定状态。
     */
    private StatusResult collectStatus(Repository repo, Path root) throws IOException {
        // 获取排序后的 index entries（index 格式要求按路径排序）
        List<com.weixiao.repo.Index.Entry> indexEntries = new ArrayList<>(repo.getIndex().getEntries());
        Map<String, com.weixiao.repo.Index.Entry> pathToEntry = new LinkedHashMap<>();
        for (com.weixiao.repo.Index.Entry e : indexEntries) {
            pathToEntry.put(e.getPath(), e);
        }
        Set<String> trackedPaths = pathToEntry.keySet();
        
        // 递归收集工作区所有文件路径（相对路径，排序）
        List<WorkspaceFile> workspaceFiles = new ArrayList<>();
        collectWorkspaceFiles(repo, root, "", trackedPaths, workspaceFiles);
        Collections.sort(workspaceFiles, Comparator.comparing(WorkspaceFile::getRelativePath));
        
        // 比较工作区和 index
        return compareWorkspaceToIndex(repo.getWorkspace(), indexEntries, pathToEntry, workspaceFiles);
    }

    /**
     * 使用双指针同步遍历方式比较工作区和 index，返回 Modified、Deleted 和 Untracked。
     * 该方法可被复用，未来也可用于比较 index 和 HEAD。
     *
     * @param workspace     工作区，用于读取文件内容和获取文件元数据
     * @param indexEntries  排序后的 index entries 列表
     * @param pathToEntry   path 到 index Entry 的映射，用于查找文件对应的 entry
     * @param workspaceFiles 排序后的工作区文件列表
     * @return 包含 Modified、Deleted、Untracked 的 StatusResult
     */
    private StatusResult compareWorkspaceToIndex(Workspace workspace,
                                                List<com.weixiao.repo.Index.Entry> indexEntries,
                                                Map<String, com.weixiao.repo.Index.Entry> pathToEntry,
                                                List<WorkspaceFile> workspaceFiles) throws IOException {
        StatusResult result = new StatusResult();
        int indexIdx = 0;
        int workspaceIdx = 0;
        
        while (indexIdx < indexEntries.size() || workspaceIdx < workspaceFiles.size()) {
            String indexPath = indexIdx < indexEntries.size() ? indexEntries.get(indexIdx).getPath() : null;
            String workspacePath = workspaceIdx < workspaceFiles.size() ? workspaceFiles.get(workspaceIdx).getRelativePath() : null;
            
            if (workspacePath == null) {
                // 工作区遍历完毕，index 中剩余的都是 deleted
                result.getDeleted().add(indexPath);
                log.debug("deleted: {}", indexPath);
                indexIdx++;
            } else if (indexPath == null) {
                // index 遍历完毕，工作区中剩余的都是 untracked
                WorkspaceFile wsFile = workspaceFiles.get(workspaceIdx);
                if (wsFile.isDirectory()) {
                    result.getUntracked().add(workspacePath + "/");
                } else {
                    result.getUntracked().add(workspacePath);
                }
                workspaceIdx++;
            } else {
                int cmp = workspacePath.compareTo(indexPath);
                if (cmp < 0) {
                    // 工作区路径 < index 路径：工作区文件不在 index 中 -> untracked
                    WorkspaceFile wsFile = workspaceFiles.get(workspaceIdx);
                    if (wsFile.isDirectory()) {
                        result.getUntracked().add(workspacePath + "/");
                    } else {
                        result.getUntracked().add(workspacePath);
                    }
                    workspaceIdx++;
                } else if (cmp > 0) {
                    // 工作区路径 > index 路径：index 中的文件在工作区不存在 -> deleted
                    result.getDeleted().add(indexPath);
                    log.debug("deleted: {}", indexPath);
                    indexIdx++;
                } else {
                    // 工作区路径 == index 路径：已跟踪文件 -> 检查是否 modified
                    WorkspaceFile wsFile = workspaceFiles.get(workspaceIdx);
                    if (!wsFile.isDirectory()) {
                        com.weixiao.repo.Index.Entry entry = pathToEntry.get(indexPath);
                        // 使用缓存的 stat 信息，避免重复文件系统访问
                        if (isFileModified(workspace, wsFile.getAbsolutePath(), entry,
                                wsFile.getStat(), wsFile.getMode(), wsFile.getSize(), indexPath)) {
                            result.getModified().add(indexPath);
                        }
                    }
                    indexIdx++;
                    workspaceIdx++;
                }
            }
        }
        
        return result;
    }

    /**
     * 递归收集工作区所有文件路径（用于双指针比较）。
     * 对于 tracked 的文件，在遍历时缓存 stat 信息，避免后续比较时重复读取文件系统。
     * 对于完全未跟踪的目录，只记录目录本身（带尾部 /），不展开内容。
     */
    private void collectWorkspaceFiles(Repository repo, Path dir, String prefix,
                                       Set<String> trackedPaths,
                                       List<WorkspaceFile> result) throws IOException {
        Workspace workspace = repo.getWorkspace();
        List<Path> entries = workspace.listEntries(dir);
        Collections.sort(entries, (a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        
        for (Path child : entries) {
            String name = child.getFileName().toString();
            String relativePath = prefix.isEmpty() ? name : prefix + "/" + name;
            
            if (Files.isRegularFile(child)) {
                // 对于 tracked 文件，缓存 stat 信息（参考 Git 实现）
                if (trackedPaths.contains(relativePath)) {
                    long fileSize = Files.size(child);
                    String mode = workspace.getFileMode(child);
                    com.weixiao.repo.Index.IndexStat stat = Workspace.getFileStat(child);
                    result.add(new WorkspaceFile(relativePath, child, false, fileSize, mode, stat));
                } else {
                    // untracked 文件不需要 stat，节省内存
                    result.add(new WorkspaceFile(relativePath, child, false));
                }
            } else if (Files.isDirectory(child)) {
                if (!hasTrackedFilesUnder(relativePath, trackedPaths)) {
                    // 目录下无被跟踪文件，整体列为未跟踪（如果非空）
                    if (hasAnyFileUnder(workspace, child)) {
                        result.add(new WorkspaceFile(relativePath, child, true));
                    }
                } else {
                    // 目录下有被跟踪文件，递归收集
                    collectWorkspaceFiles(repo, child, relativePath, trackedPaths, result);
                }
            }
        }
    }

    /** 工作区文件/目录信息，用于双指针比较。对于 tracked 文件缓存 stat 信息以提高性能。 */
    private static final class WorkspaceFile {
        private final String relativePath;
        private final Path absolutePath;
        private final boolean directory;
        private final Long fileSize;  // null 表示未缓存（untracked 文件）
        private final String mode;    // null 表示未缓存
        private final com.weixiao.repo.Index.IndexStat stat;  // null 表示未缓存

        /** 构造 untracked 文件/目录（不缓存 stat）。 */
        WorkspaceFile(String relativePath, Path absolutePath, boolean directory) {
            this(relativePath, absolutePath, directory, null, null, null);
        }

        /** 构造 tracked 文件（缓存 stat）。 */
        WorkspaceFile(String relativePath, Path absolutePath, boolean directory,
                     Long fileSize, String mode, com.weixiao.repo.Index.IndexStat stat) {
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
        com.weixiao.repo.Index.IndexStat getStat() { return stat; }
    }

    /** 一次遍历得到的 Modified、Deleted 与 Untracked 结果。 */
    private static final class StatusResult {
        private final List<String> modified = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();
        private final List<String> untracked = new ArrayList<>();

        List<String> getModified() { return modified; }
        List<String> getDeleted() { return deleted; }
        List<String> getUntracked() { return untracked; }
    }

    /**
     * 判断 index 中是否有该目录或其子路径被跟踪。
     * 若目录下没有任何文件出现在 index 中，则该目录整体可列为未跟踪，不展开内容。
     */
    private static boolean hasTrackedFilesUnder(String dirPath, Set<String> trackedPaths) {
        String prefix = dirPath.isEmpty() ? "" : dirPath + "/";
        return trackedPaths.stream()
                .anyMatch(p -> p.equals(dirPath) || p.startsWith(prefix));
    }

    /**
     * 判断目录下是否包含至少一个普通文件（递归）。Git 只关心内容，空目录不列为未跟踪。
     */
    private boolean hasAnyFileUnder(Workspace workspace, Path dir) throws IOException {
        for (Path child : workspace.listEntries(dir)) {
            if (Files.isRegularFile(child)) {
                return true;
            }
            if (Files.isDirectory(child) && hasAnyFileUnder(workspace, child)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
