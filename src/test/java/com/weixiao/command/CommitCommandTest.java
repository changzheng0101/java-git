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
        Files.writeString(f, "hello", StandardCharsets.UTF_8);

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
}
