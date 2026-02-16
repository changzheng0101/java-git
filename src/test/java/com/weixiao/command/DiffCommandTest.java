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
}
