package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.JitTestUtil;
import com.weixiao.JitTestUtil.ExecuteResult;
import com.weixiao.repo.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommitCommand 测试")
class CommitCommandTest {

    private static final CommandLine JIT = Jit.createCommandLine();

    /**
     * 在无 .git 的目录执行 jit commit 时应失败，并在 stderr 中提示 not a jit repository。
     * 示例：对空目录 /tmp/empty 执行 "jit commit -m msg /tmp/empty" → 退出码非 0，stderr 含 "not a jit repository"。
     */
    @Test
    @DisplayName("在非仓库目录执行 commit 失败并提示 not a jit repository")
    void commit_outsideRepo_fails(@TempDir Path dir) {
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "commit", "-m", "msg");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("not a jit repository");
    }

    /**
     * 在已 init 的仓库中，未 add 任何文件时 commit 应失败，提示 no changes added to commit。
     */
    @Test
    @DisplayName("index 为空时 commit 失败并提示 no changes added")
    void commit_emptyIndex_fails(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "msg");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("no changes added to commit");
    }

    /**
     * 在已 init 的仓库中 add 文件后再 commit -m，应成功并写入对象与 refs/heads/master。
     * 与 Git 一致：commit 只提交 index 中的内容。
     */
    @Test
    @DisplayName("init 后 add 文件再 commit -m 成功并写入对象与 ref")
    void commit_afterAdd_succeeds(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path f = tempDir.resolve("hello.txt");
        Files.write(f, "hello".getBytes(StandardCharsets.UTF_8));
        ExecuteResult addResult = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "hello.txt");
        assertThat(addResult.getExitCode()).as("add err: %s", addResult.getErr()).isEqualTo(0);

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "first commit");
        assertThat(result.getExitCode()).as("commit out: %s err: %s", result.getOutput(), result.getErr()).isEqualTo(0);
        assertThat(result.getOutput()).contains("first commit");

        Repository repo = Repository.find(tempDir);
        assertThat(repo).isNotNull();
        String head = repo.getRefs().readHead();
        assertThat(head).isNotNull();
        assertThat(head).hasSize(40);
        assertThat(repo.getDatabase().exists(head)).isTrue();
    }

    /**
     * 在嵌套目录结构中 add 后再 commit，应成功创建包含子目录的 Tree（从 index 构建）。
     */
    @Test
    @DisplayName("add 嵌套目录后 commit 支持嵌套 tree 结构")
    void commit_withNestedDirectories_succeeds(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");

        Path dir1 = tempDir.resolve("dir1");
        Files.createDirectories(dir1);
        Files.write(dir1.resolve("file1.txt"), "content1".getBytes(StandardCharsets.UTF_8));
        Path subdir = dir1.resolve("subdir");
        Files.createDirectories(subdir);
        Files.write(subdir.resolve("file2.txt"), "content2".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("root.txt"), "root content".getBytes(StandardCharsets.UTF_8));

        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "dir1", "root.txt");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "nested commit");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("nested commit");

        Repository repo = Repository.find(tempDir);
        assertThat(repo).isNotNull();
        String head = repo.getRefs().readHead();
        assertThat(head).isNotNull();
        assertThat(head).hasSize(40);
        assertThat(repo.getDatabase().exists(head)).isTrue();
        com.weixiao.repo.ObjectDatabase.RawObject commitObj = repo.getDatabase().load(head);
        assertThat(commitObj.getType()).isEqualTo("commit");
    }

    /**
     * 在多层嵌套目录中 add 后再 commit，验证从 index 构建的 tree 正确。
     */
    @Test
    @DisplayName("add 深层嵌套目录后 commit 成功")
    void commit_withDeepNestedDirectories_succeeds(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path deepDir = tempDir.resolve("a").resolve("b").resolve("c").resolve("d");
        Files.createDirectories(deepDir);
        Files.write(deepDir.resolve("file.txt"), "deep content".getBytes(StandardCharsets.UTF_8));

        ExecuteResult addResult = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "a");
        assertThat(addResult.getExitCode()).as("add err: %s", addResult.getErr()).isEqualTo(0);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "deep nested");
        assertThat(result.getExitCode()).as("commit out: %s err: %s", result.getOutput(), result.getErr()).isEqualTo(0);

        Repository repo = Repository.find(tempDir);
        assertThat(repo).isNotNull();
        String head = repo.getRefs().readHead();
        assertThat(head).isNotNull();
        assertThat(repo.getDatabase().exists(head)).isTrue();
    }
}
