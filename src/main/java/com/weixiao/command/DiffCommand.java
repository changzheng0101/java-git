package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.obj.Blob;
import com.weixiao.repo.Index;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Repository;
import com.weixiao.repo.StatusResult;
import com.weixiao.utils.DiffUtils;
import com.weixiao.utils.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * jit diff - 显示 index 与 workspace 或 index 与 HEAD 的差异。
 * 无参数：index vs workspace（changed 文件）。
 * --cached / --staged：index vs HEAD。
 */
@Command(name = "diff", mixinStandardHelpOptions = true, description = "显示工作区与暂存区或暂存区与 HEAD 的差异")
public class DiffCommand implements Runnable, IExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(DiffCommand.class);
    private static final String DEV_NULL = "/dev/null";

    @ParentCommand
    private Jit jit;

    @Option(names = {"--cached", "--staged"}, description = "对比 index 与 HEAD（暂存区与最近提交）")
    private boolean cached;

    private int exitCode = 0;

    @Override
    public void run() {
        exitCode = 0;
        Path start = jit.getStartPath();
        Repository repo = Repository.find(start);
        if (repo == null) {
            System.err.println("fatal: not a jit repository (or any of the parent directories): .git");
            exitCode = 1;
            return;
        }
        try {
            repo.getIndex().load();
            StatusResult status = repo.getStatus();
            if (cached) {
                diffHeadIndex(repo, status);
            } else {
                diffIndexWorkspace(repo, status);
            }
        } catch (IOException e) {
            log.error("diff failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }

    /**
     * 对比 index 与 workspace：对 workspaceModified、workspaceDeleted 中的路径输出 diff。
     */
    private void diffIndexWorkspace(Repository repo, StatusResult status) throws IOException {
        List<String> paths = new ArrayList<>();
        paths.addAll(status.getWorkspaceModified());
        paths.addAll(status.getWorkspaceDeleted());
        Collections.sort(paths);
        for (String path : paths) {
            Index.Entry indexEntry = repo.getIndex().getEntryForPath(path);
            if (indexEntry == null) continue;
            boolean deleted = status.getWorkspaceDeleted().contains(path);
            String oldContent = blobContent(repo, indexEntry.getOid());
            String newContent;
            String newMode;
            String newOid;
            if (deleted) {
                newContent = "";
                newMode = null;
                newOid = null;
            } else {
                Path fullPath = repo.getRoot().resolve(path);
                byte[] bytes = repo.getWorkspace().readFile(fullPath);
                newContent = new String(bytes, StandardCharsets.UTF_8);
                newMode = repo.getWorkspace().getFileMode(fullPath);
                newOid = computeBlobOid(bytes);
            }
            printDiff(repo, path, indexEntry.getOid(), indexEntry.getMode(),
                    newOid, newMode,
                    oldContent, deleted ? null : newContent, false, deleted);
        }
    }

    /**
     * 对比 HEAD 与 index：对 indexAdded、indexModified、indexDeleted 中的路径输出 diff。
     */
    private void diffHeadIndex(Repository repo, StatusResult status) throws IOException {
        List<String> paths = new ArrayList<>();
        paths.addAll(status.getIndexAdded());
        paths.addAll(status.getIndexModified());
        paths.addAll(status.getIndexDeleted());
        Collections.sort(paths);
        Map<String, String> headPathToOid = status.getHeadPathToOid();
        Map<String, String> headPathToMode = status.getHeadPathToMode();
        for (String path : paths) {
            String headOid = headPathToOid.get(path);
            Index.Entry indexEntry = repo.getIndex().getEntryForPath(path);
            boolean added = status.getIndexAdded().contains(path);
            boolean deleted = status.getIndexDeleted().contains(path);
            String oldOid = added ? null : headOid;
            String oldMode = added ? null : headPathToMode.get(path);
            if (oldMode == null && oldOid != null) oldMode = "100644";
            String newOid = deleted ? null : (indexEntry != null ? indexEntry.getOid() : null);
            String newMode = deleted ? null : (indexEntry != null ? indexEntry.getMode() : null);
            String oldContent = (oldOid != null) ? blobContent(repo, oldOid) : "";
            String newContent = (newOid != null) ? blobContent(repo, newOid) : "";
            printDiff(repo, path, oldOid, oldMode, newOid, newMode, oldContent, newContent, added, deleted);
        }
    }

    private static String blobContent(Repository repo, String oid) throws IOException {
        if (oid == null) return "";
        ObjectDatabase.RawObject raw = repo.getDatabase().load(oid);
        if (!"blob".equals(raw.getType())) return "";
        return new String(raw.getBody(), StandardCharsets.UTF_8);
    }

    private static String computeBlobOid(byte[] data) {
        Blob blob = new Blob(data);
        byte[] body = blob.toBytes();
        String header = blob.getType() + " " + body.length + "\0";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[headerBytes.length + body.length];
        System.arraycopy(headerBytes, 0, content, 0, headerBytes.length);
        System.arraycopy(body, 0, content, headerBytes.length, body.length);
        try {
            return HexUtils.bytesToHex(MessageDigest.getInstance("SHA-1").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 输出一个文件的 diff 块。
     *
     * @param path      相对路径
     * @param oldOid    左侧 blob oid（null 表示 /dev/null）
     * @param oldMode   左侧 mode（展示用，null 表示无）
     * @param newOid    右侧 blob oid（null 表示 /dev/null）
     * @param newMode   右侧 mode（展示用，index 侧）
     * @param oldContent 左侧内容
     * @param newContent 右侧内容
     * @param isNewFile  是否为新文件（--- /dev/null）
     * @param isDeleted  是否为删除（+++ /dev/null）
     */
    private void printDiff(Repository repo, String path,
                           String oldOid, String oldMode, String newOid, String newMode,
                           String oldContent, String newContent,
                           boolean isNewFile, boolean isDeleted) {
        System.out.println("diff --git a/" + path + " b/" + path);
        if (isDeleted) {
            System.out.println("deleted file mode " + (oldMode != null ? oldMode : "100644"));
            System.out.println("index " + (oldOid != null ? shortOid(oldOid) : "0000000") + "..0000000");
            System.out.println("--- a/" + path);
            System.out.println("+++ " + DEV_NULL);
            printDiffBody(oldContent, "");
            return;
        }
        if (isNewFile) {
            System.out.println("new file mode " + (newMode != null ? newMode : "100644"));
            System.out.println("index 0000000.." + (newOid != null ? shortOid(newOid) : "0000000"));
            System.out.println("--- " + DEV_NULL);
            System.out.println("+++ b/" + path);
            printDiffBody("", newContent != null ? newContent : "");
            return;
        }
        boolean modeChanged = oldMode != null && newMode != null && !oldMode.equals(newMode);
        if (modeChanged) {
            System.out.println("old mode " + oldMode);
            System.out.println("new mode " + newMode);
        }
        if (oldOid != null && newOid != null && !oldOid.equals(newOid)) {
            System.out.println("index " + shortOid(oldOid) + ".." + shortOid(newOid) + " " + (newMode != null ? newMode : "100644"));
        }
        System.out.println("--- a/" + path);
        System.out.println("+++ b/" + path);
        printDiffBody(oldContent != null ? oldContent : "", newContent != null ? newContent : "");
    }

    private static String shortOid(String oid) {
        return oid != null && oid.length() >= 7 ? oid.substring(0, 7) : oid;
    }

    private static void printDiffBody(String oldContent, String newContent) {
        List<String> a = oldContent.isEmpty() ? Collections.emptyList() : DiffUtils.lines(oldContent);
        List<String> b = newContent.isEmpty() ? Collections.emptyList() : DiffUtils.lines(newContent);
        List<DiffUtils.Edit> edits = DiffUtils.diff(a, b);
        int countA = a.size();
        int countB = b.size();
        if (countA == 0 && countB == 0) return;
        System.out.println("@@ -1," + countA + " +1," + countB + " @@");
        for (DiffUtils.Edit e : edits) {
            String prefix = e.getType() == DiffUtils.EditType.DEL ? "-" : (e.getType() == DiffUtils.EditType.INS ? "+" : " ");
            String line = e.getLine();
            if (!line.endsWith("\n")) line = line + "\n";
            System.out.print(prefix + line);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
