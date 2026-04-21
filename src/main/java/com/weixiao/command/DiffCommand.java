package com.weixiao.command;

import com.weixiao.model.DiffSide;
import com.weixiao.obj.TreeEntry;
import com.weixiao.repo.Index;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Repository;
import com.weixiao.model.StatusResult;
import com.weixiao.repo.Workspace;
import com.weixiao.utils.Color;
import com.weixiao.utils.DiffUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * jit diff - 显示 index 与 workspace 或 index 与 HEAD 的差异。
 * 无参数：index vs workspace（changed 文件）。
 * --cached / --staged：index vs HEAD。
 */
@Command(name = "diff", mixinStandardHelpOptions = true, description = "显示工作区与暂存区或暂存区与 HEAD 的差异")
public class DiffCommand extends BaseCommand {

    private static final Logger log = LoggerFactory.getLogger(DiffCommand.class);
    private static final String DEV_NULL = "/dev/null";
    public static final String NULL_OID = "0000000";
    private static final String EMPTY_CONTENT = "";
    public static final int CONTEXT_LINES = 3; // Git 默认上下文行数

    @SuppressWarnings("unused")
    @Option(names = {"--cached", "--staged"}, description = "对比 index 与 HEAD（暂存区与最近提交）")
    private boolean cached;

    @SuppressWarnings("unused")
    @Option(names = {"--no-color"}, description = "关闭彩色输出")
    private boolean noColor;

    @SuppressWarnings("unused")
    @Option(names = {"--base", "-1"}, description = "冲突文件以 stage-1（base）为基准显示 diff")
    private boolean base;

    @SuppressWarnings("unused")
    @Option(names = {"--ours", "-2"}, description = "冲突文件以 stage-2（ours）为基准显示 diff")
    private boolean ours;

    @SuppressWarnings("unused")
    @Option(names = {"--theirs", "-3"}, description = "冲突文件以 stage-3（theirs）为基准显示 diff")
    private boolean theirs;

    @Override
    protected void initParams() {
        params = new LinkedHashMap<>();
        if (cached) {
            params.put("cached", "");
        }
        if (noColor) {
            params.put("noColor", "");
        }
        if (base) {
            params.put("stage", "1");
        }
        if (ours) {
            params.put("stage", "2");
        }
        if (theirs) {
            params.put("stage", "3");
        }
    }

    /**
     * 是否使用颜色（与 Git 一致：默认在 TTY 下开启，--no-color 关闭）
     */
    private boolean useColor() {
        if (isSet("noColor")) {
            return false;
        }
        return System.console() != null;
    }

    @Override
    protected void doRun() {
        try {
            repo.getIndex().load();
            StatusResult status = repo.getStatus();
            if (isSet("cached")) {
                diffHeadIndex(status);
            } else {
                diffIndexWorkspace(status);
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
    private void diffIndexWorkspace(StatusResult status) throws IOException {
        List<String> paths = new ArrayList<>();
        paths.addAll(status.getConflicts().keySet());
        paths.addAll(status.getWorkspaceModified());
        paths.addAll(status.getWorkspaceDeleted());
        Collections.sort(paths);

        for (String path : paths) {
            boolean isConflictPath = status.getConflicts().containsKey(path);
            if (isConflictPath) {
                printConflictDiff(path);
                continue;
            }

            Index.Entry indexEntry = repo.getIndex().getEntryForPath(path);
            boolean deleted = status.getWorkspaceDeleted().contains(path);

            DiffSide aDiffSide = new DiffSide(indexEntry.getPath(), indexEntry.getOid(), indexEntry.getMode(), repo.getDatabase().readBlobUtf8(indexEntry.getOid()));
            DiffSide bDiffSide = deleted ?
                    new DiffSide(path, NULL_OID, null, EMPTY_CONTENT) :
                    fromWorkSpace(path);

            printDiff(aDiffSide, bDiffSide);
        }
    }

    private void printConflictDiff(String path) throws IOException {
        System.out.println("* Unmerged path " + path);

        Index.Entry indexEntry = Repository.INSTANCE.getIndex().getEntryForPath(path, getStageFromParams());
        if (indexEntry == null) {
            return;
        }

        DiffSide aDiffSide = new DiffSide(path, indexEntry.getOid(), indexEntry.getMode(), repo.getDatabase().readBlobUtf8(indexEntry.getOid()));
        DiffSide bDiffSide = fromWorkSpace(path);

        printDiff(aDiffSide, bDiffSide);
    }


    /**
     * 对比 HEAD 与 index：对 indexAdded、indexModified、indexDeleted 中的路径输出 diff。
     * a对应的是HEAD中的文件
     * b对应的是index中的文件
     */
    private void diffHeadIndex(StatusResult status) throws IOException {
        List<String> paths = new ArrayList<>();
        paths.addAll(status.getIndexAdded());
        paths.addAll(status.getIndexModified());
        paths.addAll(status.getIndexDeleted());
        Collections.sort(paths);

        Map<String, TreeEntry> headPathToEntry = new HashMap<>();
        String headCommitOid = repo.getRefs().readHead();
        if (headCommitOid != null) {
            repo.collectCommitTreeTo(headCommitOid, headPathToEntry);
        }

        for (String path : paths) {
            TreeEntry headEntry = headPathToEntry.get(path);
            Index.Entry indexEntry = repo.getIndex().getEntryForPath(path);
            boolean added = status.getIndexAdded().contains(path);
            boolean deleted = status.getIndexDeleted().contains(path);

            DiffSide aDiffSide, bDiffSide;
            aDiffSide = added
                    ? new DiffSide(path, NULL_OID, null, EMPTY_CONTENT)
                    : new DiffSide(path, headEntry.getOid(), headEntry.getMode(), repo.getDatabase().readBlobUtf8(headEntry.getOid()));
            bDiffSide = deleted
                    ? new DiffSide(path, NULL_OID, null, EMPTY_CONTENT)
                    : new DiffSide(path, indexEntry.getOid(), indexEntry.getMode(), repo.getDatabase().readBlobUtf8(indexEntry.getOid()));

            printDiff(aDiffSide, bDiffSide);
        }
    }

    /**
     *
     * @param path 针对根目录的相对路径
     * @return 由当前路径构建出来的DiffSide
     */
    private static DiffSide fromWorkSpace(String path) throws IOException {
        Repository repo = Repository.INSTANCE;
        Path fullPath = repo.getRoot().resolve(path);
        byte[] bytes = repo.getWorkspace().readFile(fullPath);
        return new DiffSide(
                path,
                Repository.computeBlobOid(bytes),
                Workspace.getFileMode(fullPath),
                new String(bytes, StandardCharsets.UTF_8)
        );
    }


    /**
     * 输出一个文件的 diff 块。根据 aDiffSide / bDiffSide 是否“有文件”判断新增、删除、修改。
     * 与 Git 一致：meta 加粗、hunk 头青色、删除红、新增绿。
     */
    private void printDiff(DiffSide aDiffSide, DiffSide bDiffSide) {
        String path = aDiffSide.getPath();
        boolean color = useColor();

        String metaLine = "diff --git a/" + path + " b/" + path;
        System.out.println(color ? Color.bold(metaLine) : metaLine);

        printDiffMode(aDiffSide, bDiffSide, color);
        String modeSuffix = Objects.equals(aDiffSide.getMode(), bDiffSide.getMode()) ? (aDiffSide.getMode() != null ? aDiffSide.getMode() : "") : "";
        String indexLine = "index " + ObjectDatabase.shortOid(aDiffSide.getOid()) + ".." + ObjectDatabase.shortOid(bDiffSide.getOid()) + (modeSuffix.isEmpty() ? "" : " " + modeSuffix);
        System.out.println(color ? Color.bold(indexLine) : indexLine);

        String minusLine = "--- " + (aDiffSide.hasFile() ? "a/" + path : DEV_NULL);
        String plusLine = "+++ " + (bDiffSide.hasFile() ? "b/" + path : DEV_NULL);
        System.out.println(color ? Color.bold(minusLine) : minusLine);
        System.out.println(color ? Color.bold(plusLine) : plusLine);

        String oldContent = aDiffSide.getContent();
        String newContent = bDiffSide.getContent();
        List<DiffUtils.Line> a = DiffUtils.lines(oldContent);
        List<DiffUtils.Line> b = DiffUtils.lines(newContent);
        List<DiffUtils.Edit> edits = DiffUtils.diffLines(a, b);
        if (edits.isEmpty() || (a.isEmpty() && b.isEmpty())) {
            return;
        }
        for (Hunk hunk : groupEditsIntoHunks(edits)) {
            hunk.print();
        }
    }

    private void printDiffMode(DiffSide aDiffSide, DiffSide bDiffSide, boolean color) {
        if (bDiffSide.getMode() == null) {
            String s = "deleted file mode " + aDiffSide.getMode();
            System.out.println(color ? Color.bold(s) : s);
        } else if (aDiffSide.getMode() == null) {
            String s = "new file mode " + bDiffSide.getMode();
            System.out.println(color ? Color.bold(s) : s);
        } else if (!Objects.equals(aDiffSide.getMode(), bDiffSide.getMode())) {
            System.out.println(color ? Color.bold("old mode " + aDiffSide.getMode()) : "old mode " + aDiffSide.getMode());
            System.out.println(color ? Color.bold("new mode " + bDiffSide.getMode()) : "new mode " + bDiffSide.getMode());
        }
    }


    /**
     * 一个 diff hunk：包含变更区域及其上下文；负责依次输出 hunk 头与正文行。
     */
    private final class Hunk {
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

        /** Git 风格 hunk 头：{@code @@ -startA,countA +startB,countB @@} */
        String headerLine() {
            return "@@ -" + startA + "," + countA + " +" + startB + "," + countB + " @@";
        }

        /**
         * 本 hunk 的无 ANSI patch 文本（hunk 头一行 + 带 {@code -}/{@code +}/空格 前缀的正文行）。
         */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(headerLine()).append('\n');
            for (DiffUtils.Edit e : edits) {
                sb.append(editLinePlain(e));
            }
            return sb.toString();
        }

        /**
         * 写出到终端：{@link #useColor()} 为 false 时输出 {@link #toString()}；为 true 时对 hunk 头与删除/插入行着色。
         */
        void print() {
            if (!useColor()) {
                System.out.print(this);
                return;
            }
            System.out.println(Color.cyan(headerLine()));
            for (DiffUtils.Edit e : edits) {
                printEditLineColored(e);
            }
        }

        private static String editLinePlain(DiffUtils.Edit e) {
            String prefix = e.getType() == DiffUtils.EditType.DEL ? "-"
                    : (e.getType() == DiffUtils.EditType.INS ? "+" : " ");
            String line = e.getLine();
            if (!line.endsWith("\n")) {
                line = line + "\n";
            }
            return prefix + line;
        }

        private static void printEditLineColored(DiffUtils.Edit e) {
            String fullLine = editLinePlain(e);
            if (e.getType() == DiffUtils.EditType.DEL) {
                System.out.print(Color.deletion(fullLine));
            } else if (e.getType() == DiffUtils.EditType.INS) {
                System.out.print(Color.insertion(fullLine));
            } else {
                System.out.print(Color.context(fullLine));
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


    private int getStageFromParams() {
        String stage = params.get("stage");
        if (stage == null) {
            return -1;
        }
        return Integer.parseInt(stage);
    }
}
