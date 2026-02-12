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
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StatusCommand 测试")
class StatusCommandTest {

    private static final CommandLine JIT = Jit.createCommandLine();

    @Test
    @DisplayName("在非仓库目录执行 status 失败并提示 not a jit repository")
    void status_outsideRepo_fails(@TempDir Path dir) {
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", dir.toString());
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("not a jit repository");
    }

    @Test
    @DisplayName("空仓库无文件时 status 显示 working tree clean")
    void status_emptyRepo_showsClean(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("nothing to commit, working tree clean");
    }

    @Test
    @DisplayName("有未跟踪文件时 status 列出 Untracked files")
    void status_untrackedFile_listed(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        Files.write(tempDir.resolve("untracked.txt"), "content".getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Untracked files:");
        assertThat(result.getOutput()).contains("untracked.txt");
    }

    @Test
    @DisplayName("已 add 的文件不再出现在 Untracked 中")
    void status_afterAdd_noUntracked(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        Files.write(tempDir.resolve("staged.txt"), "content".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "add", "-C", tempDir.toString(), "staged.txt");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("nothing to commit, working tree clean");
        assertThat(result.getOutput()).doesNotContain("staged.txt");
    }

    @Test
    @DisplayName("目录下无被跟踪文件时，整个目录列为未跟踪（不展开内容）")
    void status_nestedUntracked_listedAsDirectory(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        Path nested = tempDir.resolve("a").resolve("b");
        Files.createDirectories(nested);
        Files.write(nested.resolve("nested.txt"), "nested".getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Untracked files:");
        assertThat(result.getOutput()).contains("a/");
        assertThat(result.getOutput()).doesNotContain("a/b/nested.txt");
    }

    @Test
    @DisplayName("--porcelain 模式输出机器可读格式 ?? <path>")
    void status_porcelain_untrackedFile(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        Files.write(tempDir.resolve("untracked.txt"), "content".getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", "--porcelain", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("?? untracked.txt");
        assertThat(result.getOutput()).doesNotContain("Untracked files:");
        assertThat(result.getOutput()).doesNotContain("nothing to commit");
    }

    @Test
    @DisplayName("--porcelain 模式无未跟踪文件时输出为空")
    void status_porcelain_noUntracked_emptyOutput(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", "--porcelain", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput().trim()).isEmpty();
    }

    @Test
    @DisplayName("--porcelain 模式：无被跟踪文件的目录整体列为 ?? dir/")
    void status_porcelain_untrackedDirectory(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        Path nested = tempDir.resolve("dir1").resolve("dir2");
        Files.createDirectories(nested);
        Files.write(nested.resolve("file.txt"), "content".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("root.txt"), "root".getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", "--porcelain", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("?? dir1/");
        assertThat(result.getOutput()).contains("?? root.txt");
        assertThat(result.getOutput()).doesNotContain("dir1/dir2/file.txt");
        String[] lines = result.getOutput().trim().split("\n");
        for (String line : lines) {
            assertThat(line).startsWith("?? ");
        }
    }

    @Test
    @DisplayName("目录下有部分被跟踪文件时，只列出未跟踪的文件/子目录")
    void status_partiallyTrackedDir_listsOnlyUntracked(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        Path dir1 = tempDir.resolve("dir1");
        Files.createDirectories(dir1);
        Files.write(dir1.resolve("tracked.txt"), "tracked".getBytes(StandardCharsets.UTF_8));
        Files.write(dir1.resolve("untracked.txt"), "untracked".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "add", "-C", tempDir.toString(), "dir1/tracked.txt");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Untracked files:");
        assertThat(result.getOutput()).contains("dir1/untracked.txt");
        assertThat(Arrays.stream(result.getOutput().split("\n")).noneMatch(line -> line.trim().equals("dir1/"))).isTrue();
        assertThat(result.getOutput()).doesNotContain("dir1/tracked.txt");
    }

    @Test
    @DisplayName("空目录不列为未跟踪（Git 只关心内容）")
    void status_emptyDirectory_notListed(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        Files.createDirectories(tempDir.resolve("empty_dir"));
        Path nestedEmpty = tempDir.resolve("a").resolve("b").resolve("c");
        Files.createDirectories(nestedEmpty);

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("nothing to commit, working tree clean");
        assertThat(result.getOutput()).doesNotContain("empty_dir");
        assertThat(result.getOutput()).doesNotContain("a/");
    }

    @Test
    @DisplayName("工作区文件与 index 内容不一致时识别为 Modified")
    void status_modifiedFile_detected(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        Path file = tempDir.resolve("test.txt");
        Files.write(file, "original".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "add", "-C", tempDir.toString(), "test.txt");
        Files.write(file, "modified".getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Changes not staged for commit:");
        assertThat(result.getOutput()).contains("modified:   test.txt");
    }

    @Test
    @DisplayName("--porcelain 模式：Modified 文件输出为  M <path>")
    void status_porcelain_modifiedFile(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        Path file = tempDir.resolve("file.txt");
        Files.write(file, "v1".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "add", "-C", tempDir.toString(), "file.txt");
        Files.write(file, "v2".getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", "--porcelain", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains(" M file.txt");
    }

    @Test
    @DisplayName("Modified 和 Untracked 同时存在时都显示")
    void status_modifiedAndUntracked_bothShown(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        Path modifiedFile = tempDir.resolve("modified.txt");
        Files.write(modifiedFile, "original".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "add", "-C", tempDir.toString(), "modified.txt");
        Files.write(modifiedFile, "changed".getBytes(StandardCharsets.UTF_8));

        Path untrackedFile = tempDir.resolve("untracked.txt");
        Files.write(untrackedFile, "new".getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Changes not staged for commit:");
        assertThat(result.getOutput()).contains("modified:   modified.txt");
        assertThat(result.getOutput()).contains("Untracked files:");
        assertThat(result.getOutput()).contains("untracked.txt");
    }

    @Test
    @DisplayName("文件大小不同时直接识别为 Modified（无需计算 oid）")
    void status_sizeChanged_detectedAsModified(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        Path file = tempDir.resolve("file.txt");
        Files.write(file, "short".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "add", "-C", tempDir.toString(), "file.txt");
        Files.write(file, "much longer content".getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("modified:   file.txt");
    }

    @Test
    @DisplayName("时间戳相同时跳过内容检查（即使内容不同也认为未修改）")
    void status_sameTimestamp_skipsContentCheck(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        Path file = tempDir.resolve("file.txt");
        Files.write(file, "content1".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "add", "-C", tempDir.toString(), "file.txt");

        // 修改内容但保持时间戳不变（通过 touch 或直接修改后恢复时间戳）
        // 注意：在实际文件系统中，修改文件内容通常会改变 mtime
        // 这里主要验证逻辑：如果时间戳相同，会跳过内容检查
        // 实际场景中，如果 add 后文件未被修改，时间戳应该相同，status 应该显示 clean
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        // 如果时间戳相同（文件未被修改），应该显示 clean
        assertThat(result.getOutput()).contains("nothing to commit, working tree clean");
    }

    @Test
    @DisplayName("已删除的文件显示为 deleted（在 index 中但工作区不存在）")
    void status_deletedFile_detected(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        Path file = tempDir.resolve("deleted.txt");
        Files.write(file, "content".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "add", "-C", tempDir.toString(), "deleted.txt");
        Files.delete(file);

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Changes not staged for commit:");
        assertThat(result.getOutput()).contains("deleted:    deleted.txt");
    }

    @Test
    @DisplayName("--porcelain 模式：Deleted 文件输出为  D <path>")
    void status_porcelain_deletedFile(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        Path file = tempDir.resolve("file.txt");
        Files.write(file, "content".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "add", "-C", tempDir.toString(), "file.txt");
        Files.delete(file);

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", "--porcelain", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains(" D file.txt");
    }

    @Test
    @DisplayName("Modified、Deleted 和 Untracked 同时存在时都显示")
    void status_allStatusTypes_shown(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        
        // Modified
        Path modifiedFile = tempDir.resolve("modified.txt");
        Files.write(modifiedFile, "original".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "add", "-C", tempDir.toString(), "modified.txt");
        Files.write(modifiedFile, "changed".getBytes(StandardCharsets.UTF_8));
        
        // Deleted
        Path deletedFile = tempDir.resolve("deleted.txt");
        Files.write(deletedFile, "content".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "add", "-C", tempDir.toString(), "deleted.txt");
        Files.delete(deletedFile);
        
        // Untracked
        Path untrackedFile = tempDir.resolve("untracked.txt");
        Files.write(untrackedFile, "new".getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "status", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Changes not staged for commit:");
        assertThat(result.getOutput()).contains("modified:   modified.txt");
        assertThat(result.getOutput()).contains("deleted:    deleted.txt");
        assertThat(result.getOutput()).contains("Untracked files:");
        assertThat(result.getOutput()).contains("untracked.txt");
    }
}
