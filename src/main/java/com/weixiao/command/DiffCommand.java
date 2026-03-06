package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.model.DiffSide;
import com.weixiao.repo.Index;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Repository;
import com.weixiao.model.StatusResult;
import com.weixiao.utils.DiffColor;
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
    public static final int CONTEXT_LINES = 3; // Git 默认上下文行数


    @ParentCommand
    private Jit jit;

    @Option(names = {"--cached", "--staged"}, description = "对比 index 与 HEAD（暂存区与最近提交）")
    private boolean cached;

    @Option(names = {"--no-color"}, description = "关闭彩色输出")
    private boolean noColor;

    private int exitCode = 0;

    /** 是否使用颜色（与 Git 一致：默认在 TTY 下开启，--no-color 关闭） */
    private boolean useColor() {
        if (noColor) return false;
        return System.console() != null;
    }

    @Override
    public void run() {
        exitCode = 0;
        Path start = jit.getStartPath();
        Repository repo = Repository.find(start);
        if (repo == null) {
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
        } catch (Exception e) {
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
        var raw = repo.getDatabase().load(oid);
        if (!"blob".equals(raw.getType())) return "";
        return new String(raw.toBytes(), StandardCharsets.UTF_8);
    }


    /**
     * 输出一个文件的 diff 块。根据 aDiffSide / bDiffSide 是否“有文件”判断新增、删除、修改。
     * 与 Git 一致：meta 加粗、hunk 头青色、删除红、新增绿。
     */
    private void printDiff(DiffSide aDiffSide, DiffSide bDiffSide) {
        String path = aDiffSide.getPath();
        boolean color = useColor();

        String metaLine = "diff --git a/" + path + " b/" + path;
        System.out.println(color ? DiffColor.bold(metaLine) : metaLine);

        printDiffMode(aDiffSide, bDiffSide, color);
        String modeSuffix = Objects.equals(aDiffSide.getMode(), bDiffSide.getMode()) ? (aDiffSide.getMode() != null ? aDiffSide.getMode() : "") : "";
        String indexLine = "index " + shortOid(aDiffSide.getOid()) + ".." + shortOid(bDiffSide.getOid()) + (modeSuffix.isEmpty() ? "" : " " + modeSuffix);
        System.out.println(color ? DiffColor.bold(indexLine) : indexLine);

        String minusLine = "--- " + (aDiffSide.hasFile() ? "a/" + path : DEV_NULL);
        String plusLine = "+++ " + (bDiffSide.hasFile() ? "b/" + path : DEV_NULL);
        System.out.println(color ? DiffColor.bold(minusLine) : minusLine);
        System.out.println(color ? DiffColor.bold(plusLine) : plusLine);
        printDiffBody(aDiffSide.getContent(), bDiffSide.getContent(), color);
    }

    private void printDiffMode(DiffSide aDiffSide, DiffSide bDiffSide, boolean color) {
        if (bDiffSide.getMode() == null) {
            String s = "deleted file mode " + aDiffSide.getMode();
            System.out.println(color ? DiffColor.bold(s) : s);
        } else if (aDiffSide.getMode() == null) {
            String s = "new file mode " + bDiffSide.getMode();
            System.out.println(color ? DiffColor.bold(s) : s);
        } else if (!Objects.equals(aDiffSide.getMode(), bDiffSide.getMode())) {
            System.out.println(color ? DiffColor.bold("old mode " + aDiffSide.getMode()) : "old mode " + aDiffSide.getMode());
            System.out.println(color ? DiffColor.bold("new mode " + bDiffSide.getMode()) : "new mode " + bDiffSide.getMode());
        }
    }

    private String shortOid(String oid) {
        return oid != null && oid.length() >= 7 ? oid.substring(0, 7) : oid;
    }


    /**
     * 一个 diff hunk：包含变更区域及其上下文。
     */
    private static final class Hunk {
        final int startA;  // a 文件起始行号（1-based）
        final int countA;  // a 文件该 hunk 的行数
        final int startB;  // b 文件起始行号（1-based）
        final int countB;  // b 文件该 hunk 的行数
        final List<DiffUtils.Edit> edits;  // 该 hunk 的编辑列表

        Hunk(int startA, int countA, int startB, int countB, List<DiffUtils.Edit> edits) {
            this.startA = startA;
            this.countA = countA;
            this.startB = startB;
            this.countB = countB;
            this.edits = edits;
        }
    }

    private void printDiffBody(String oldContent, String newContent, boolean color) {
        List<String> a = oldContent.isEmpty() ? Collections.emptyList() : DiffUtils.lines(oldContent);
        List<String> b = newContent.isEmpty() ? Collections.emptyList() : DiffUtils.lines(newContent);
        List<DiffUtils.Edit> edits = DiffUtils.diff(a, b);

        if (edits.isEmpty() || (a.isEmpty() && b.isEmpty())) return;

        List<Hunk> hunks = groupEditsIntoHunks(edits);
        for (Hunk hunk : hunks) {
            String hunkHeader = "@@ -" + hunk.startA + "," + hunk.countA + " +" + hunk.startB + "," + hunk.countB + " @@";
            System.out.println(color ? DiffColor.cyan(hunkHeader) : hunkHeader);
            for (DiffUtils.Edit e : hunk.edits) {
                String prefix = e.getType() == DiffUtils.EditType.DEL ? "-" : (e.getType() == DiffUtils.EditType.INS ? "+" : " ");
                String line = e.getLine();
                if (!line.endsWith("\n")) line = line + "\n";
                String fullLine = prefix + line;
                if (color) {
                    if (e.getType() == DiffUtils.EditType.DEL) {
                        System.out.print(DiffColor.deletion(fullLine));
                    } else if (e.getType() == DiffUtils.EditType.INS) {
                        System.out.print(DiffColor.insertion(fullLine));
                    } else {
                        System.out.print(DiffColor.context(fullLine));
                    }
                } else {
                    System.out.print(fullLine);
                }
            }
        }
    }

    /**
     * 将 Edit 列表分组为多个 hunks
     * 每个 hunk 包含变更及前后 CONTEXT_LINES 行上下文；若两段变更之间相同行 ≤ 2*CONTEXT_LINES 则合并为一个 hunk。
     */
    private List<Hunk> groupEditsIntoHunks(List<DiffUtils.Edit> edits) {
        List<Hunk> hunks = new ArrayList<>();
        int offset = 0;

        while (offset < edits.size()) {
            // 跳过开头的相同行
            while (offset < edits.size() && edits.get(offset).getType() == DiffUtils.EditType.EQL) {
                offset++;
            }
            if (offset >= edits.size()) break;

            // 向前回退 CONTEXT_LINES 行作为上下文
            int hunkStart = Math.max(0, offset - CONTEXT_LINES);
            // startA 和 startB 是从1开始
            int startA = 1 + countLinesA(edits, 0, hunkStart - 1);
            int startB = 1 + countLinesB(edits, 0, hunkStart - 1);

            // 从 hunkStart 起向后扩展，直到“最后一段变更”后再出现 CONTEXT_LINES 行相同行；中间若遇到新变更则继续扩展（合并）
            int hunkEnd = buildHunkEnd(edits, hunkStart);
            List<DiffUtils.Edit> hunkEdits = new ArrayList<>(edits.subList(hunkStart, hunkEnd + 1));
            int countA = countLinesA(edits, hunkStart, hunkEnd);
            int countB = countLinesB(edits, hunkStart, hunkEnd);

            hunks.add(new Hunk(startA, countA, startB, countB, hunkEdits));
            offset = hunkEnd + 1;
        }
        return hunks;
    }

    /**
     * 从 start 起扩展 hunk 结尾：遇到变更则继续（合并）；在见过变更后，再出现 CONTEXT_LINES 行相同则结束。
     */
    private int buildHunkEnd(List<DiffUtils.Edit> edits, int start) {
        int counter = -1;  // 未见过变更前不结束
        int i = start;
        while (i < edits.size()) {
            if (edits.get(i).getType() != DiffUtils.EditType.EQL) {
                counter = 2 * CONTEXT_LINES;
            } else if (counter >= 0) {
                counter--;
                if (counter == 0) return Math.max(0, i - CONTEXT_LINES);
            }
            i++;
        }
        return edits.size() - 1;
    }

    private int countLinesA(List<DiffUtils.Edit> edits, int from, int to) {
        if (from > to) return 0;
        int n = 0;
        for (int i = from; i <= to; i++) {
            if (edits.get(i).getType() == DiffUtils.EditType.DEL || edits.get(i).getType() == DiffUtils.EditType.EQL) {
                n++;
            }
        }
        return n;
    }

    private int countLinesB(List<DiffUtils.Edit> edits, int from, int to) {
        if (from > to) return 0;
        int n = 0;
        for (int i = from; i <= to; i++) {
            if (edits.get(i).getType() == DiffUtils.EditType.INS || edits.get(i).getType() == DiffUtils.EditType.EQL) {
                n++;
            }
        }
        return n;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
