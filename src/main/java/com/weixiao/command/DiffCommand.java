package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.model.DiffSide;
import com.weixiao.repo.Index;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Repository;
import com.weixiao.model.StatusResult;
import com.weixiao.utils.DiffUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
    public static final String NULL_OID = "0000000";
    private static final String EMPTY_CONTENT = "";

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
     * a为index对应元数据
     * b为workspace对应元数据
     */
    private void diffIndexWorkspace(Repository repo, StatusResult status) throws IOException {
        List<String> paths = new ArrayList<>();
        paths.addAll(status.getWorkspaceModified());
        paths.addAll(status.getWorkspaceDeleted());
        Collections.sort(paths);
        for (String path : paths) {
            Index.Entry indexEntry = repo.getIndex().getEntryForPath(path);
            if (indexEntry == null) continue;
            DiffSide aDiffSide = new DiffSide(
                    indexEntry.getPath(),
                    indexEntry.getOid(),
                    indexEntry.getMode(),
                    blobContent(repo, indexEntry.getOid()));

            DiffSide bDiffSide;
            if (status.getWorkspaceDeleted().contains(path)) {
                bDiffSide = new DiffSide(path, NULL_OID, null, EMPTY_CONTENT);
            } else {
                Path fullPath = repo.getRoot().resolve(path);
                byte[] bytes = repo.getWorkspace().readFile(fullPath);
                bDiffSide = new DiffSide(
                        path,
                        Repository.computeBlobOid(bytes),
                        repo.getWorkspace().getFileMode(fullPath),
                        new String(bytes, StandardCharsets.UTF_8));
            }

            printDiff(aDiffSide, bDiffSide);
        }
    }

    /**
     * 对比 HEAD 与 index：对 indexAdded、indexModified、indexDeleted 中的路径输出 diff。
     * a对应的是HEAD中的文件
     * b对应的是index中的文件
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

            DiffSide aDiffSide, bDiffSide;
            aDiffSide = added
                    ? new DiffSide(path, NULL_OID, null, EMPTY_CONTENT)
                    : new DiffSide(path, headOid, headPathToMode.get(path), blobContent(repo, headPathToOid.get(path)));
            bDiffSide = deleted
                    ? new DiffSide(path, NULL_OID, null, EMPTY_CONTENT)
                    : new DiffSide(path, indexEntry.getOid(), indexEntry.getMode(), blobContent(repo, indexEntry.getOid()));

            printDiff(aDiffSide, bDiffSide);
        }
    }

    private static String blobContent(Repository repo, String oid) throws IOException {
        if (oid == null) return "";
        ObjectDatabase.RawObject raw = repo.getDatabase().load(oid);
        if (!"blob".equals(raw.getType())) return "";
        return new String(raw.getBody(), StandardCharsets.UTF_8);
    }


    /**
     * 输出一个文件的 diff 块。根据 aDiffSide / bDiffSide 是否“有文件”判断新增、删除、修改。
     * e.g
     * diff --git a/deleted.txt b/deleted.txt
     * new file mode 100644
     * index 0000000..7898192
     * --- /dev/null
     * +++ b/deleted.txt
     * diff_content
     *
     * @param aDiffSide 左侧（a/）：oid、mode、content，无文件时 oid 为 NULL_OID
     * @param bDiffSide 右侧（b/）：oid、mode、content，无文件时 oid 为 NULL_OID
     */
    private void printDiff(DiffSide aDiffSide, DiffSide bDiffSide) {
        String path = aDiffSide.getPath();
        System.out.println("diff --git a/" + path + " b/" + path);

        printDiffMode(aDiffSide, bDiffSide);
        System.out.printf("index %s..%s", shortOid(aDiffSide.getOid()), shortOid(bDiffSide.getOid()));
        System.out.println(Objects.equals(aDiffSide.getMode(), bDiffSide.getMode()) ? aDiffSide.getMode() : "");

        System.out.println("--- " + (aDiffSide.hasFile() ? "a/" + path : DEV_NULL));
        System.out.println("+++ " + (bDiffSide.hasFile() ? "b/" + path : DEV_NULL));
        printDiffBody(aDiffSide.getContent(), bDiffSide.getContent());
    }

    private void printDiffMode(DiffSide aDiffSide, DiffSide bDiffSide) {
        if (bDiffSide.getMode() == null) {
            System.out.printf("deleted file mode %s", aDiffSide.getMode());
        } else if (aDiffSide.getMode() == null) {
            System.out.printf("new file mode %s", bDiffSide.getMode());
        } else if (!Objects.equals(aDiffSide.getMode(), bDiffSide.getMode())) {
            System.out.println("old mode " + aDiffSide.getMode());
            System.out.println("new mode " + bDiffSide.getMode());
        }
    }

    private String shortOid(String oid) {
        return oid != null && oid.length() >= 7 ? oid.substring(0, 7) : oid;
    }

    private void printDiffBody(String oldContent, String newContent) {
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
