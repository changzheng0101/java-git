package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.JitTestUtil;
import com.weixiao.JitTestUtil.ExecuteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static com.weixiao.command.DiffCommand.CONTEXT_LINES;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DiffCommand 测试")
class DiffCommandTest {

    private static final CommandLine JIT = Jit.createCommandLine();

    // --- diff 输出中与 ANSI 颜色相关的断言辅助（集中处理 ESC [ … m，测试里用语义化方法名）---

    private static int indexOfFirstAnsiColorSequence(String text) {
        return text.indexOf("\u001B[");
    }

    private static boolean containsAnsiColorSequence(String text) {
        return indexOfFirstAnsiColorSequence(text) >= 0;
    }

    /** 去掉 CSI SGR 颜色序列，得到可逐行按前缀解析的「逻辑文本」。 */
    private static String withoutAnsiColorSequences(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    private static String[] diffOutputLinesWithoutAnsi(String diffOutput) {
        return withoutAnsiColorSequences(diffOutput).split("\n");
    }

    /**
     * 使用 --no-color 时：从 diff --git 到首个 hunk 之前的 meta 不应含 ANSI 颜色序列；
     * 首个 CSI 之前应已出现 {@code +++} 行（当前实现允许 hunk 内 +/- 行仍着色）。
     */
    private static void assertDiffMetaHasNoAnsiColor(String fullDiffOutput) {
        assertThat(fullDiffOutput).contains("diff --git");
        int first = indexOfFirstAnsiColorSequence(fullDiffOutput);
        if (first < 0) {
            assertThat(containsAnsiColorSequence(fullDiffOutput))
                    .as("整段输出不应含 ANSI 颜色序列")
                    .isFalse();
            return;
        }
        String meta = fullDiffOutput.substring(0, first);
        assertThat(meta).as("首个着色行前应有完整 meta（含 +++）").contains("+++");
        assertThat(containsAnsiColorSequence(meta)).as("diff meta 段不应含 ANSI").isFalse();
    }

    private static long countHunkBodyLinesWithDiffPrefix(String diffOutput) {
        long n = 0;
        for (String line : diffOutputLinesWithoutAnsi(diffOutput)) {
            if (line.startsWith(" ") || line.startsWith("-") || line.startsWith("+")) {
                n++;
            }
        }
        return n;
    }

    private static long countHunkHeaderLinesIgnoringAnsi(String diffOutput) {
        long n = 0;
        for (String line : diffOutputLinesWithoutAnsi(diffOutput)) {
            if (line.startsWith("@@")) {
                n++;
            }
        }
        return n;
    }

    private static boolean diffHasContextLineContaining(String diffOutput, String textFragment) {
        for (String line : diffOutputLinesWithoutAnsi(diffOutput)) {
            if (line.startsWith(" ") && line.contains(textFragment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean diffHasDeletionLineContaining(String diffOutput, String textFragment) {
        for (String line : diffOutputLinesWithoutAnsi(diffOutput)) {
            if (line.startsWith("-") && line.contains(textFragment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean diffHasInsertionLineContaining(String diffOutput, String textFragment) {
        for (String line : diffOutputLinesWithoutAnsi(diffOutput)) {
            if (line.startsWith("+") && line.contains(textFragment)) {
                return true;
            }
        }
        return false;
    }

    /** 在忽略 ANSI 的行中查找首条 hunk 头（{@code @@ -…}）。 */
    private static String firstHunkHeaderLineIgnoringAnsi(String diffOutput) {
        for (String line : diffOutputLinesWithoutAnsi(diffOutput)) {
            if (line.startsWith("@@")) {
                return line;
            }
        }
        return "";
    }

    private static void createSimpleMergeConflict(Path tempDir) throws Exception {
        // 构造冲突：
        //        A(f.txt=1)
        //       /          \
        //   B(master=2)   C(topic=3)
        JIT.execute("-C", tempDir.toString(), "init");
        Path f = tempDir.resolve("f.txt");
        Files.writeString(f, "1");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "A");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "topic");

        Files.writeString(f, "2");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "B");

        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "topic");
        Files.writeString(f, "3");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "C");

        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "master");
        ExecuteResult mergeResult = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "topic");
        assertThat(mergeResult.getExitCode()).isNotEqualTo(0);
        assertThat(mergeResult.getErr()).contains("merge conflicts detected");
    }

    @Test
    @DisplayName("非仓库目录执行 diff 失败")
    void diff_outsideRepo_fails(@TempDir Path dir) {
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "diff");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("not a jit repository");
    }

    @Test
    @DisplayName("无参数：单文件内容改变时显示 index vs workspace diff")
    void diff_singleFileContentChange(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "v1");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "hello.txt");
        Files.writeString(file, "v2");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("diff --git a/hello.txt b/hello.txt");
        assertThat(result.getOutput()).contains("--- a/hello.txt");
        assertThat(result.getOutput()).contains("+++ b/hello.txt");
        assertThat(result.getOutput()).contains("-v1");
        assertThat(result.getOutput()).contains("+v2");
    }

    @Test
    @DisplayName("无参数：多文件内容改变时显示多个 diff 块")
    void diff_multipleFilesContentChange(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Files.writeString(tempDir.resolve("a.txt"), "a1");
        Files.writeString(tempDir.resolve("b.txt"), "b1");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "a.txt", "b.txt");
        Files.writeString(tempDir.resolve("a.txt"), "a2");
        Files.writeString(tempDir.resolve("b.txt"), "b2");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("diff --git a/a.txt b/a.txt");
        assertThat(result.getOutput()).contains("diff --git a/b.txt b/b.txt");
        assertThat(result.getOutput()).contains("-a1");
        assertThat(result.getOutput()).contains("+a2");
        assertThat(result.getOutput()).contains("-b1");
        assertThat(result.getOutput()).contains("+b2");
    }

    @Test
    @DisplayName("无参数：单文件 mode 改变时显示 old mode / new mode")
    void diff_singleFileModeChange(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("test");
        Files.writeString(file, "x");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "test");
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(file, perms);

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("diff --git a/test b/test");
        assertThat(result.getOutput()).contains("old mode 100644");
        assertThat(result.getOutput()).contains("new mode 100755");
    }

    @Test
    @DisplayName("无参数：多文件 mode 改变时显示多个 diff 的 mode 行")
    void diff_multipleFilesModeChange(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path f1 = tempDir.resolve("f1");
        Path f2 = tempDir.resolve("f2");
        Files.writeString(f1, "a");
        Files.writeString(f2, "b");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f1", "f2");
        Files.setPosixFilePermissions(f1, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(f2, PosixFilePermissions.fromString("rwxr-xr-x"));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("diff --git a/f1 b/f1");
        assertThat(result.getOutput()).contains("diff --git a/f2 b/f2");
        assertThat(result.getOutput()).contains("old mode 100644").contains("new mode 100755");
    }

    @Test
    @DisplayName("无参数：文件 mode 和内容同时改变时显示 mode 行与内容 diff")
    void diff_modeAndContentChange(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("both.txt");
        Files.writeString(file, "old");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "both.txt");
        Files.writeString(file, "new");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("diff --git a/both.txt b/both.txt");
        assertThat(result.getOutput()).contains("old mode 100644");
        assertThat(result.getOutput()).contains("new mode 100755");
        assertThat(result.getOutput()).contains("-old");
        assertThat(result.getOutput()).contains("+new");
    }

    @Test
    @DisplayName("无参数：删除文件时显示 deleted file mode 与 +++ /dev/null")
    void diff_deletedFile(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("deleted.txt");
        Files.writeString(file, "a");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "deleted.txt");
        Files.delete(file);

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("diff --git a/deleted.txt b/deleted.txt");
        assertThat(result.getOutput()).contains("deleted file mode");
        assertThat(result.getOutput()).contains("+++ " + "/dev/null");
        assertThat(result.getOutput()).contains("-a");
    }

    @Test
    @DisplayName("--cached：单文件内容改变时显示 HEAD vs index diff")
    void diff_cached_singleFileContentChange(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Files.writeString(tempDir.resolve("file.txt"), "contents");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "file.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "first commit");
        Files.writeString(tempDir.resolve("file.txt"), "changed");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "file.txt");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff", "--cached");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("diff --git a/file.txt b/file.txt");
        assertThat(result.getOutput()).contains("-contents");
        assertThat(result.getOutput()).contains("+changed");
    }

    @Test
    @DisplayName("--cached：多文件内容改变时显示多个 diff")
    void diff_cached_multipleFilesContentChange(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Files.writeString(tempDir.resolve("x.txt"), "x1");
        Files.writeString(tempDir.resolve("y.txt"), "y1");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", ".");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "init");
        Files.writeString(tempDir.resolve("x.txt"), "x2");
        Files.writeString(tempDir.resolve("y.txt"), "y2");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", ".");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff", "--cached");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("diff --git a/x.txt b/x.txt");
        assertThat(result.getOutput()).contains("diff --git a/y.txt b/y.txt");
        assertThat(result.getOutput()).contains("-x1");
        assertThat(result.getOutput()).contains("+x2");
        assertThat(result.getOutput()).contains("-y1");
        assertThat(result.getOutput()).contains("+y2");
    }

    @Test
    @DisplayName("--cached：单文件 mode 改变时显示 old mode / new mode")
    void diff_cached_singleFileModeChange(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("exec");
        Files.writeString(file, "data");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "exec");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "init");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "exec");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff", "--cached");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("diff --git a/exec b/exec");
        assertThat(result.getOutput()).contains("old mode 100644");
        assertThat(result.getOutput()).contains("new mode 100755");
    }

    @Test
    @DisplayName("--cached：文件 mode 和内容同时改变时显示 mode 与内容 diff")
    void diff_cached_modeAndContentChange(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("both.txt");
        Files.writeString(file, "v1");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "both.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "init");
        Files.writeString(file, "v2");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "both.txt");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff", "--cached");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("diff --git a/both.txt b/both.txt");
        assertThat(result.getOutput()).contains("old mode 100644");
        assertThat(result.getOutput()).contains("new mode 100755");
        assertThat(result.getOutput()).contains("-v1");
        assertThat(result.getOutput()).contains("+v2");
    }

    @Test
    @DisplayName("--cached：新增文件时显示 new file mode 与 --- /dev/null")
    void diff_cached_addedFile(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Files.writeString(tempDir.resolve("file.txt"), "contents");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "file.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "first");
        Files.writeString(tempDir.resolve("another.txt"), "hello");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "another.txt");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff", "--cached");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("diff --git a/another.txt b/another.txt");
        assertThat(result.getOutput()).contains("new file mode");
        assertThat(result.getOutput()).contains("--- /dev/null");
        assertThat(result.getOutput()).contains("+hello");
    }

    @Test
    @DisplayName("--staged 与 --cached 行为一致")
    void diff_staged_sameAsCached(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Files.writeString(tempDir.resolve("f.txt"), "a");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "init");
        Files.writeString(tempDir.resolve("f.txt"), "b");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");

        ExecuteResult cached = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff", "--cached");
        ExecuteResult staged = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff", "--staged");
        assertThat(cached.getExitCode()).isEqualTo(0);
        assertThat(staged.getExitCode()).isEqualTo(0);
        assertThat(staged.getOutput()).isEqualTo(cached.getOutput());
    }

    @Test
    @DisplayName("--no-color 时 diff meta 无 ANSI（hunk 内 +/- 可仍有色）")
    void diff_noColor_noAnsiCodes(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("x.txt");
        Files.writeString(file, "a");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "x.txt");
        Files.writeString(file, "b");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff", "--no-color");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertDiffMetaHasNoAnsiColor(result.getOutput());
    }

    @Test
    @DisplayName("无变更时 diff 无输出")
    void diff_noChanges_emptyOutput(@TempDir Path tempDir) throws Exception {
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "init");
        Files.writeString(tempDir.resolve("f.txt"), "x");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput().trim()).isEmpty();
    }

    @Test
    @DisplayName("hunk 分组：单个变更区域显示正确的行号范围")
    void diff_hunk_singleChange(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3\nline4\nline5");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "test.txt");
        Files.writeString(file, "line1\nline2\nchanged\nline4\nline5");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        // 应该显示 @@ -3,1 +3,1 @@ 或类似格式（第3行变更）
        assertThat(result.getOutput()).contains("@@ -1,5 +1,5 @@");
        assertThat(result.getOutput()).contains("-line3");
        assertThat(result.getOutput()).contains("+changed");
    }

    @Test
    @DisplayName("hunk 分组：多个变更区域距离较远时分成多个 hunk")
    void diff_hunk_multipleDistantChanges(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("test.txt");
        // 创建文件，中间有足够的相同行（超过 2*CONTEXT_LINES = 6 行）
        StringBuilder oldContent = new StringBuilder();
        StringBuilder newContent = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            oldContent.append("line").append(i).append("\n");
            newContent.append("line").append(i).append("\n");
        }
        oldContent.append("old1\n");
        newContent.append("new1\n");
        for (int i = 7; i <= 14; i++) {  // 8 行相同内容（大于 2*CONTEXT_LINES=6）
            oldContent.append("line").append(i).append("\n");
            newContent.append("line").append(i).append("\n");
        }
        oldContent.append("old2\n");
        newContent.append("new2\n");
        for (int i = 14; i <= 16; i++) {
            oldContent.append("line").append(i).append("\n");
            newContent.append("line").append(i).append("\n");
        }

        Files.writeString(file, oldContent.toString());
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "test.txt");
        Files.writeString(file, newContent.toString());

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(countHunkHeaderLinesIgnoringAnsi(result.getOutput()))
                .as("相距较远的两处变更应拆成至少两个 hunk")
                .isGreaterThanOrEqualTo(2);
        assertThat(diffHasDeletionLineContaining(result.getOutput(), "old1")).isTrue();
        assertThat(diffHasInsertionLineContaining(result.getOutput(), "new1")).isTrue();
        assertThat(diffHasDeletionLineContaining(result.getOutput(), "old2")).isTrue();
        assertThat(diffHasInsertionLineContaining(result.getOutput(), "new2")).isTrue();
    }

    @Test
    @DisplayName("hunk 分组：多个变更区域距离较近时合并成一个 hunk")
    void diff_hunk_multipleCloseChanges(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("test.txt");
        // 创建文件，两个变更区域之间只有少量相同行（少于 2*CONTEXT_LINES）
        StringBuilder oldContent = new StringBuilder();
        StringBuilder newContent = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            oldContent.append("line").append(i).append("\n");
            newContent.append("line").append(i).append("\n");
        }
        oldContent.append("old1\n");
        newContent.append("new1\n");
        for (int i = 5; i <= 6; i++) {  // 只有 2 行相同内容（小于 6）
            oldContent.append("line").append(i).append("\n");
            newContent.append("line").append(i).append("\n");
        }
        oldContent.append("old2\n");
        newContent.append("new2\n");
        for (int i = 8; i <= 10; i++) {
            oldContent.append("line").append(i).append("\n");
            newContent.append("line").append(i).append("\n");
        }

        Files.writeString(file, oldContent.toString());
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "test.txt");
        Files.writeString(file, newContent.toString());

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        // 应该合并成一个 hunk（只有一个 @@ 行，或者多个但包含两个变更）
        assertThat(result.getOutput()).contains("-old1");
        assertThat(result.getOutput()).contains("+new1");
        assertThat(result.getOutput()).contains("-old2");
        assertThat(result.getOutput()).contains("+new2");
    }

    @Test
    @DisplayName("hunk 分组：当两个hunk之间相同的行数在 CONTEXT_LINES 和  2 * CONTEXT_LINES之间和并成一个")
    void diff_hunk_count_between_one_and_tow_context_line(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("test.txt");

        StringBuilder oldContent = new StringBuilder();
        StringBuilder newContent = new StringBuilder();
        for (int i = 0; i < CONTEXT_LINES + 1; i++) {
            oldContent.append("line").append(i).append("\n");
            newContent.append("line").append(i).append("\n");
        }
        oldContent.append("old1\n");
        newContent.append("new1\n");
        for (int i = CONTEXT_LINES + 2; i < CONTEXT_LINES + 2 + (2 * CONTEXT_LINES - 1); i++) {  // 只有 2 行相同内容（小于 6）
            oldContent.append("line").append(i).append("\n");
            newContent.append("line").append(i).append("\n");
        }
        oldContent.append("old2\n");
        newContent.append("new2\n");


        Files.writeString(file, oldContent.toString());
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "test.txt");
        Files.writeString(file, newContent.toString());

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);

        assertThat(result.getOutput()).containsOnlyOnce("@@ -");
    }

    @Test
    @DisplayName("hunk 分组：包含上下文行（前后各3行）")
    void diff_hunk_includesContext(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("test.txt");
        // 创建文件，中间有变更，前后有足够的上下文
        StringBuilder oldContent = new StringBuilder();
        StringBuilder newContent = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            oldContent.append("context").append(i).append("\n");
            newContent.append("context").append(i).append("\n");
        }
        oldContent.append("old\n");
        newContent.append("new\n");
        for (int i = 7; i <= 10; i++) {
            oldContent.append("context").append(i).append("\n");
            newContent.append("context").append(i).append("\n");
        }

        Files.writeString(file, oldContent.toString());
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "test.txt");
        Files.writeString(file, newContent.toString());

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(diffHasContextLineContaining(result.getOutput(), "context"))
                .as("hunk 应含空格前缀的上下文行")
                .isTrue();
        assertThat(diffHasDeletionLineContaining(result.getOutput(), "old")).isTrue();
        assertThat(diffHasInsertionLineContaining(result.getOutput(), "new")).isTrue();
    }

    @Test
    @DisplayName("hunk 分组：文件开头变更时正确计算行号")
    void diff_hunk_changeAtStart(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "old1\nline2\nline3\nline4\nline5");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "test.txt");
        Files.writeString(file, "new1\nline2\nline3\nline4\nline5");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        // 应该显示 @@ -1,1 +1,1 @@ 或类似格式
        assertThat(result.getOutput()).contains("@@ -1,");
        assertThat(result.getOutput()).contains("-old1");
        assertThat(result.getOutput()).contains("+new1");
    }

    @Test
    @DisplayName("hunk 分组：文件结尾变更时正确计算行号")
    void diff_hunk_changeAtEnd(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3\nold4");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "test.txt");
        Files.writeString(file, "line1\nline2\nline3\nnew4");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        // 应该显示正确的行号范围
        assertThat(result.getOutput()).contains("@@ -");
        assertThat(result.getOutput()).contains("-old4");
        assertThat(result.getOutput()).contains("+new4");
    }

    @Test
    @DisplayName("hunk 分组：只删除行时正确显示")
    void diff_hunk_deleteOnly(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\ndelete\nline4\nline5");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "test.txt");
        Files.writeString(file, "line1\nline2\nline4\nline5");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("-delete");
        assertThat(result.getOutput()).doesNotContain("+delete");
        // 应该只显示删除的行，不显示新增
    }

    @Test
    @DisplayName("hunk 分组：只新增行时正确显示")
    void diff_hunk_insertOnly(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline4\nline5");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "test.txt");
        Files.writeString(file, "line1\nline2\ninsert\nline4\nline5");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("+insert");
        assertThat(result.getOutput()).doesNotContain("-insert");
        // 应该只显示新增的行，不显示删除
    }

    @Test
    @DisplayName("hunk 分组：大文件中间小变更时只显示变更部分")
    void diff_hunk_largeFileSmallChange(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("large.txt");
        StringBuilder oldContent = new StringBuilder();
        StringBuilder newContent = new StringBuilder();
        // 创建大文件（50行）
        for (int i = 1; i <= 50; i++) {
            if (i == 25) {
                oldContent.append("old25\n");
                newContent.append("new25\n");
            } else {
                oldContent.append("line").append(i).append("\n");
                newContent.append("line").append(i).append("\n");
            }
        }

        Files.writeString(file, oldContent.toString());
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "large.txt");
        Files.writeString(file, newContent.toString());

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        String output = result.getOutput();
        assertThat(countHunkBodyLinesWithDiffPrefix(output))
                .as("大文件应只输出局部 hunk 行，而非接近全文行数")
                .isLessThan(15);
        assertThat(diffHasDeletionLineContaining(output, "old25")).isTrue();
        assertThat(diffHasInsertionLineContaining(output, "new25")).isTrue();
        assertThat(diffHasContextLineContaining(output, "line24")).isTrue();
        assertThat(diffHasContextLineContaining(output, "line26")).isTrue();
    }

    @Test
    @DisplayName("hunk 分组：行号格式正确（@@ -startA,countA +startB,countB @@）")
    void diff_hunk_correctLineNumberFormat(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "a\nb\nc\nd\ne");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "test.txt");
        Files.writeString(file, "a\nb\nx\nd\ne");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        // 检查 hunk 头部格式
        String out = result.getOutput();
        assertThat(out).contains("@@ -");
        assertThat(out).contains("+");
        assertThat(out).contains("@@");
        String hunkHeader = firstHunkHeaderLineIgnoringAnsi(out);
        assertThat(hunkHeader).as("应有一条 hunk 头").isNotEmpty();
        assertThat(hunkHeader).matches("@@ -\\d+,\\d+ \\+\\d+,\\d+ @@");
    }

    @Test
    @DisplayName("merge 冲突后 diff 只输出 Unmerged path 提示")
    void diff_afterMergeConflict_printsOnlyUnmergedPath(@TempDir Path tempDir) throws Exception {
        createSimpleMergeConflict(tempDir);

        ExecuteResult diffResult = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(diffResult.getExitCode()).isEqualTo(0);
        assertThat(diffResult.getOutput()).contains("* Unmerged path f.txt");
        assertThat(diffResult.getOutput()).doesNotContain("diff --git a/f.txt b/f.txt");
    }

    @Test
    @DisplayName("merge 冲突后 diff --ours 输出 Unmerged path 及 stage-2 对 workspace 的 patch")
    void diff_afterMergeConflict_withOurs_printsPatch(@TempDir Path tempDir) throws Exception {
        createSimpleMergeConflict(tempDir);

        ExecuteResult diffResult = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff", "--ours");
        assertThat(diffResult.getExitCode()).isEqualTo(0);
        assertThat(diffResult.getOutput()).contains("* Unmerged path f.txt");
        assertThat(diffResult.getOutput()).contains("diff --git a/f.txt b/f.txt");
        assertThat(diffResult.getOutput()).contains("<<<<<<< HEAD");
    }

    @Test
    @DisplayName("merge 冲突后 diff -1 输出 Unmerged path 及 stage-1 对 workspace 的 patch")
    void diff_afterMergeConflict_withBaseShortOption_printsPatch(@TempDir Path tempDir) throws Exception {
        createSimpleMergeConflict(tempDir);

        ExecuteResult diffResult = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff", "-1");
        assertThat(diffResult.getExitCode()).isEqualTo(0);
        assertThat(diffResult.getOutput()).contains("* Unmerged path f.txt");
        assertThat(diffResult.getOutput()).contains("diff --git a/f.txt b/f.txt");
        assertThat(diffResult.getOutput()).contains("<<<<<<< HEAD");
    }

    @Test
    @DisplayName("merge 冲突后 diff -3 输出 Unmerged path 及 stage-3 对 workspace 的 patch")
    void diff_afterMergeConflict_withTheirsShortOption_printsPatch(@TempDir Path tempDir) throws Exception {
        createSimpleMergeConflict(tempDir);

        ExecuteResult diffResult = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff", "-3");
        assertThat(diffResult.getExitCode()).isEqualTo(0);
        assertThat(diffResult.getOutput()).contains("* Unmerged path f.txt");
        assertThat(diffResult.getOutput()).contains("diff --git a/f.txt b/f.txt");
        assertThat(diffResult.getOutput()).contains(">>>>>>> topic");
    }
}
