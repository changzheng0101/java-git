package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.JitTestUtil;
import com.weixiao.JitTestUtil.ExecuteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
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
        Files.write(file, "v1".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "hello.txt");
        Files.write(file, "v2".getBytes(StandardCharsets.UTF_8));

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
        Files.write(tempDir.resolve("a.txt"), "a1".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("b.txt"), "b1".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "a.txt", "b.txt");
        Files.write(tempDir.resolve("a.txt"), "a2".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("b.txt"), "b2".getBytes(StandardCharsets.UTF_8));

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
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8));
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
        Files.write(f1, "a".getBytes(StandardCharsets.UTF_8));
        Files.write(f2, "b".getBytes(StandardCharsets.UTF_8));
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
        Files.write(file, "old".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "both.txt");
        Files.write(file, "new".getBytes(StandardCharsets.UTF_8));
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
        Files.write(file, "a".getBytes(StandardCharsets.UTF_8));
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
        Files.write(tempDir.resolve("file.txt"), "contents".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "file.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "first commit");
        Files.write(tempDir.resolve("file.txt"), "changed".getBytes(StandardCharsets.UTF_8));
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
        Files.write(tempDir.resolve("x.txt"), "x1".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("y.txt"), "y1".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", ".");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "init");
        Files.write(tempDir.resolve("x.txt"), "x2".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("y.txt"), "y2".getBytes(StandardCharsets.UTF_8));
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
        Files.write(file, "data".getBytes(StandardCharsets.UTF_8));
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
        Files.write(file, "v1".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "both.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "init");
        Files.write(file, "v2".getBytes(StandardCharsets.UTF_8));
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
        Files.write(tempDir.resolve("file.txt"), "contents".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "file.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "first");
        Files.write(tempDir.resolve("another.txt"), "hello".getBytes(StandardCharsets.UTF_8));
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
        Files.write(tempDir.resolve("f.txt"), "a".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "init");
        Files.write(tempDir.resolve("f.txt"), "b".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");

        ExecuteResult cached = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff", "--cached");
        ExecuteResult staged = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff", "--staged");
        assertThat(cached.getExitCode()).isEqualTo(0);
        assertThat(staged.getExitCode()).isEqualTo(0);
        assertThat(staged.getOutput()).isEqualTo(cached.getOutput());
    }

    @Test
    @DisplayName("无变更时 diff 无输出")
    void diff_noChanges_emptyOutput(@TempDir Path tempDir) throws Exception {
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "init");
        Files.write(tempDir.resolve("f.txt"), "x".getBytes(StandardCharsets.UTF_8));
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
        // 应该有两个 hunk（两个 @@ 行）
        String[] outputLines = result.getOutput().split("\n");
        long hunkCount = 0;
        for (String line : outputLines) {
            if (line.startsWith("@@")) {
                hunkCount++;
            }
        }
        assertThat(hunkCount).isGreaterThanOrEqualTo(2);
        assertThat(result.getOutput()).contains("-old1");
        assertThat(result.getOutput()).contains("+new1");
        assertThat(result.getOutput()).contains("-old2");
        assertThat(result.getOutput()).contains("+new2");
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
        StringBuilder shouldContainText = new StringBuilder();
        for (int i = 0; i < CONTEXT_LINES + 1; i++) {
            oldContent.append("line").append(i).append("\n");
            newContent.append("line").append(i).append("\n");
        }
        oldContent.append("old1\n");
        newContent.append("new1\n");
        for (int i = CONTEXT_LINES + 2; i < CONTEXT_LINES + 2 + (2 * CONTEXT_LINES - 1); i++) {  // 只有 2 行相同内容（小于 6）
            shouldContainText.append("line").append(i).append("\n");
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

        Files.write(file, oldContent.toString().getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "test.txt");
        Files.write(file, newContent.toString().getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        // 应该包含上下文行（以空格开头）
        String[] lines = result.getOutput().split("\n");
        boolean hasContext = false;
        boolean hasChange = false;
        for (String line : lines) {
            if (line.startsWith(" ") && line.contains("context")) {
                hasContext = true;
            }
            if (line.startsWith("-") && line.contains("old")) {
                hasChange = true;
            }
            if (line.startsWith("+") && line.contains("new")) {
                hasChange = true;
            }
        }
        assertThat(hasContext).isTrue();
        assertThat(hasChange).isTrue();
    }

    @Test
    @DisplayName("hunk 分组：文件开头变更时正确计算行号")
    void diff_hunk_changeAtStart(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("test.txt");
        Files.write(file, "old1\nline2\nline3\nline4\nline5".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "test.txt");
        Files.write(file, "new1\nline2\nline3\nline4\nline5".getBytes(StandardCharsets.UTF_8));

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
        Files.write(file, "line1\nline2\nline3\nold4".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "test.txt");
        Files.write(file, "line1\nline2\nline3\nnew4".getBytes(StandardCharsets.UTF_8));

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
        Files.write(file, "line1\nline2\ndelete\nline4\nline5".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "test.txt");
        Files.write(file, "line1\nline2\nline4\nline5".getBytes(StandardCharsets.UTF_8));

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
        // 应该只显示变更部分和上下文，不应该显示所有50行
        String output = result.getOutput();
        String[] outputLines = output.split("\n");
        long lineCount = 0;
        for (String line : outputLines) {
            if (line.startsWith(" ") || line.startsWith("-") || line.startsWith("+")) {
                lineCount++;
            }
        }
        // hunk 应该只包含变更行 + 上下文（最多 3+1+3 = 7 行左右）
        assertThat(lineCount).isLessThan(15);  // 远小于 50
        assertThat(output).contains("-old25");
        assertThat(output).contains("+new25");
        // 应该包含上下文
        assertThat(output).contains("line24");
        assertThat(output).contains("line26");
    }

    @Test
    @DisplayName("hunk 分组：行号格式正确（@@ -startA,countA +startB,countB @@）")
    void diff_hunk_correctLineNumberFormat(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path file = tempDir.resolve("test.txt");
        Files.write(file, "a\nb\nc\nd\ne".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "test.txt");
        Files.write(file, "a\nb\nx\nd\ne".getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "diff");
        assertThat(result.getExitCode()).isEqualTo(0);
        // 检查 hunk 头部格式
        assertThat(result.getOutput()).contains("@@ -");
        assertThat(result.getOutput()).contains("+");
        assertThat(result.getOutput()).contains("@@");
        // 格式应该是 @@ -数字,数字 +数字,数字 @@
        String[] lines = result.getOutput().split("\n");
        for (String line : lines) {
            if (line.startsWith("@@")) {
                assertThat(line).matches("@@ -\\d+,\\d+ \\+\\d+,\\d+ @@");
                break;
            }
        }
    }
}
