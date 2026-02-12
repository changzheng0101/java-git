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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            List<String> untracked = result.getUntracked();

            if (porcelain) {
                // 机器可读格式：Modified 为 " M <path>"，Untracked 为 "?? <path>"
                for (String p : modified.stream().sorted().collect(Collectors.toList())) {
                    System.out.println(" M " + p);
                }
                for (String p : untracked.stream().sorted().collect(Collectors.toList())) {
                    System.out.println("?? " + p);
                }
            } else {
                // 人类可读格式
                boolean hasChanges = false;
                if (!modified.isEmpty()) {
                    System.out.println("Changes not staged for commit:");
                    for (String p : modified.stream().sorted().collect(Collectors.toList())) {
                        System.out.println("  modified:   " + p);
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
     * 采用分层检测策略以提高性能：
     * 1. 先比较文件大小（size），不同则一定 Modified
     * 2. 再比较文件权限（mode），不同则一定 Modified
     * 3. 比较时间戳（ctime/mtime），如果都相同则认为是同一文件，无需读取内容
     * 4. 最后比较文件内容（oid），只有前面都相同时才计算 oid
     *
     * @param workspace   工作区，用于读取文件内容和获取文件权限、stat
     * @param filePath    工作区文件的绝对路径
     * @param entry       index 中对应的 Entry（包含 size、mode、oid、stat）
     * @param relativePath 文件的相对路径（用于日志）
     * @return true 表示文件已修改，false 表示文件未修改
     */
    private boolean isFileModified(Workspace workspace, Path filePath,
                                   com.weixiao.repo.Index.Entry entry,
                                   String relativePath) throws IOException {
        // 1. 比较文件大小
        long fileSize = Files.size(filePath);
        if (fileSize != entry.getSize()) {
            log.debug("modified: {} size changed: {} -> {}", relativePath, entry.getSize(), fileSize);
            return true;
        }

        // 2. 比较文件权限（mode）
        String workspaceMode = workspace.getFileMode(filePath);
        if (!workspaceMode.equals(entry.getMode())) {
            log.debug("modified: {} mode changed: {} -> {}", relativePath, entry.getMode(), workspaceMode);
            return true;
        }

        // 3. 比较时间戳（ctime 和 mtime），如果都相同则认为是同一文件，无需读取内容计算 hash
        if (entry.getStat() != null) {
            com.weixiao.repo.Index.IndexStat workspaceStat = Workspace.getFileStat(filePath);
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
     * 一次遍历工作区，同时收集 Modified（在 index 中且内容不一致）和 Untracked（不在 index 中）。
     */
    private StatusResult collectStatus(Repository repo, Path root) throws IOException {
        List<com.weixiao.repo.Index.Entry> entries = repo.getIndex().getEntries();
        Map<String, com.weixiao.repo.Index.Entry> pathToEntry = new LinkedHashMap<>();
        for (com.weixiao.repo.Index.Entry e : entries) {
            pathToEntry.put(e.getPath(), e);
        }
        Set<String> trackedPaths = pathToEntry.keySet();
        StatusResult result = new StatusResult();
        collectStatusRecurse(repo, root, "", trackedPaths, pathToEntry, result);
        return result;
    }

    /**
     * 递归遍历工作区：遇到文件时，若在 index 中则检查是否 modified，否则加入 untracked；
     * 目录逻辑与原先 collectUntracked 一致。
     *
     * @param repo          仓库，用于获取 workspace
     * @param dir           当前正在遍历的目录的绝对路径
     * @param prefix        当前目录相对于仓库根的路径，使用 "/" 分隔；根目录时为空串 ""，递归时传入如 "a"、"a/b"
     * @param trackedPaths   index 中已跟踪文件的路径集合（相对仓库根、"/" 分隔），用于判断文件或目录是否被跟踪
     * @param pathToEntry   path 到 index Entry 的映射，用于查找文件对应的 entry（获取 oid）
     * @param result        存储 Modified 和 Untracked 结果
     */
    private void collectStatusRecurse(Repository repo, Path dir, String prefix,
                                      Set<String> trackedPaths,
                                      Map<String, com.weixiao.repo.Index.Entry> pathToEntry,
                                      StatusResult result) throws IOException {
        Workspace workspace = repo.getWorkspace();
        for (Path child : workspace.listEntries(dir)) {
            String name = child.getFileName().toString();
            String relativePath = prefix.isEmpty() ? name : prefix + "/" + name;

            if (Files.isRegularFile(child)) {
                com.weixiao.repo.Index.Entry entry = pathToEntry.get(relativePath);
                if (entry != null) {
                    if (isFileModified(workspace, child, entry, relativePath)) {
                        result.getModified().add(relativePath);
                    }
                } else {
                    result.getUntracked().add(relativePath);
                }
            } else if (Files.isDirectory(child)) {
                if (!hasTrackedFilesUnder(relativePath, trackedPaths)) {
                    if (hasAnyFileUnder(workspace, child)) {
                        result.getUntracked().add(relativePath + "/");
                    }
                } else {
                    collectStatusRecurse(repo, child, relativePath, trackedPaths, pathToEntry, result);
                }
            }
        }
    }

    /** 一次遍历得到的 Modified 与 Untracked 结果。 */
    private static final class StatusResult {
        private final List<String> modified = new ArrayList<>();
        private final List<String> untracked = new ArrayList<>();

        List<String> getModified() { return modified; }
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
