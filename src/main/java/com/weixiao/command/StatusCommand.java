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
                    byte[] workspaceData = workspace.readFile(child);
                    String workspaceOid = computeBlobOid(workspaceData);
                    if (!workspaceOid.equals(entry.getOid())) {
                        result.getModified().add(relativePath);
                        log.debug("modified: {} index={} workspace={}", relativePath, entry.getOid(), workspaceOid);
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
