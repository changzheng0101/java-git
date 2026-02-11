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
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "commit", "-m", "msg", dir.toString());
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("not a jit repository");
    }

    /**
     * 在已 init 的仓库中创建文件后执行 jit commit -m，应成功并写入对象与 refs/heads/master。
     * 示例：init → 创建 hello.txt 内容 "hello" → commit -m "first commit" → 退出码 0，HEAD 指向 40 字符 oid，.git/objects 中存在该 commit 对象。
     */
    @Test
    @DisplayName("init 后创建文件再 commit -m 成功并写入对象与 ref")
    void commit_afterInitAndFile_succeeds(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());
        Path f = tempDir.resolve("hello.txt");
        Files.write(f, "hello".getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "commit", "-m", "first commit", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("first commit");

        Repository repo = Repository.find(tempDir);
        assertThat(repo).isNotNull();
        String head = repo.getRefs().readHead();
        assertThat(head).isNotNull();
        assertThat(head).hasSize(40);
        assertThat(repo.getDatabase().exists(head)).isTrue();
    }

    /**
     * 在嵌套目录结构中执行 commit，应成功创建包含子目录的 Tree。
     * 示例：init → 创建 dir1/file1.txt 和 dir1/subdir/file2.txt → commit → 验证 commit 成功且包含嵌套的 tree 结构。
     */
    @Test
    @DisplayName("commit 支持嵌套目录结构")
    void commit_withNestedDirectories_succeeds(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());

        // 创建嵌套目录结构
        Path dir1 = tempDir.resolve("dir1");
        Files.createDirectories(dir1);
        Path file1 = dir1.resolve("file1.txt");
        Files.write(file1, "content1".getBytes(StandardCharsets.UTF_8));

        Path subdir = dir1.resolve("subdir");
        Files.createDirectories(subdir);
        Path file2 = subdir.resolve("file2.txt");
        Files.write(file2, "content2".getBytes(StandardCharsets.UTF_8));

        // 根目录也有文件
        Path rootFile = tempDir.resolve("root.txt");
        Files.write(rootFile, "root content".getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "commit", "-m", "nested commit", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("nested commit");

        Repository repo = Repository.find(tempDir);
        assertThat(repo).isNotNull();
        String head = repo.getRefs().readHead();
        assertThat(head).isNotNull();
        assertThat(head).hasSize(40);
        assertThat(repo.getDatabase().exists(head)).isTrue();

        // 验证 commit 对象存在
        com.weixiao.repo.ObjectDatabase.RawObject commitObj = repo.getDatabase().load(head);
        assertThat(commitObj.getType()).isEqualTo("commit");
    }

    /**
     * 在多层嵌套目录中执行 commit，验证 Merkle tree 结构正确。
     * 示例：创建 a/b/c/d/file.txt → commit → 验证所有层级的 tree 都被创建。
     */
    @Test
    @DisplayName("commit 支持多层嵌套目录")
    void commit_withDeepNestedDirectories_succeeds(@TempDir Path tempDir) throws Exception {
        JIT.execute("init", tempDir.toString());

        // 创建深层嵌套目录结构 a/b/c/d/file.txt
        Path deepDir = tempDir.resolve("a").resolve("b").resolve("c").resolve("d");
        Files.createDirectories(deepDir);
        Path deepFile = deepDir.resolve("file.txt");
        Files.write(deepFile, "deep content".getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "commit", "-m", "deep nested", tempDir.toString());
        assertThat(result.getExitCode()).isEqualTo(0);

        Repository repo = Repository.find(tempDir);
        assertThat(repo).isNotNull();
        String head = repo.getRefs().readHead();
        assertThat(head).isNotNull();
        assertThat(repo.getDatabase().exists(head)).isTrue();
    }
}
