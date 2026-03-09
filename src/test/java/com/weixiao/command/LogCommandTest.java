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

@DisplayName("LogCommand 测试")
class LogCommandTest {

    private static final CommandLine JIT = Jit.createCommandLine();

    private static void initRepoWithTwoCommits(Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path f = tempDir.resolve("f.txt");
        Files.write(f, "v1".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "first");

        Files.write(f, "v2".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "second");
    }

    @Test
    @DisplayName("在非仓库目录执行 log 失败并提示 not a jit repository")
    void log_outsideRepo_fails(@TempDir Path dir) {
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "log");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("not a jit repository");
    }

    @Test
    @DisplayName("仓库无提交时 log 失败并提示 Not a valid object name: 'HEAD'")
    void log_noCommit_fails(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "log");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("Not a valid object name: 'HEAD'");
    }

    @Test
    @DisplayName("默认 log 按从新到旧顺序输出完整 commit id 和消息")
    void log_defaultPrintsFullIdsAndMessages(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        Repository repo = Repository.find(tempDir);
        String head = repo.getRefs().readHead();

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "log");
        assertThat(result.getExitCode()).isEqualTo(0);
        String out = result.getOutput();
        // 顶部应该是第二次提交
        int idxSecond = out.indexOf("second");
        int idxFirst = out.indexOf("first");
        assertThat(idxSecond).isGreaterThanOrEqualTo(0);
        assertThat(idxFirst).isGreaterThan(idxSecond);
        assertThat(out).contains(head);
    }

    @Test
    @DisplayName("log --oneline 使用缩写 commit id 和标题行")
    void log_oneline_usesAbbrevAndTitle(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        Repository repo = Repository.find(tempDir);
        String head = repo.getRefs().readHead();
        String abbrev = head.substring(0, 7);

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "log", "--oneline");
        assertThat(result.getExitCode()).isEqualTo(0);
        String out = result.getOutput();
        assertThat(out).contains(abbrev).contains("second");
    }

    @Test
    @DisplayName("log --abbrev-commit 缩写 commit id，--no-abbrev-commit 覆盖缩写行为")
    void log_abbrev_and_noAbbrev(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        Repository repo = Repository.find(tempDir);
        String head = repo.getRefs().readHead();
        String abbrev = head.substring(0, 7);

        ExecuteResult abbrevResult = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "log", "--abbrev-commit");
        assertThat(abbrevResult.getExitCode()).isEqualTo(0);
        assertThat(abbrevResult.getOutput()).contains(abbrev);

        ExecuteResult noAbbrevResult = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "log", "--abbrev-commit", "--no-abbrev-commit");
        assertThat(noAbbrevResult.getExitCode()).isEqualTo(0);
        assertThat(noAbbrevResult.getOutput()).contains(head);
    }

    @Test
    @DisplayName("log 首行 commit 后显示 (HEAD -> master)")
    void log_showsHeadAndBranch(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "log");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("(HEAD -> master)");
    }

    @Test
    @DisplayName("log --oneline 行末也显示 (HEAD -> master)")
    void log_oneline_showsHeadAndBranch(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "log", "--oneline");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("(HEAD -> master)");
    }

    @Test
    @DisplayName("多分支指向同一 commit 时 log 显示 HEAD -> 当前分支及另一分支")
    void log_multipleBranchesAtSameCommit(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "dev");
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "log", "--oneline");
        assertThat(result.getExitCode()).isEqualTo(0);
        String out = result.getOutput();
        assertThat(out).contains("(HEAD -> master");
        assertThat(out).contains("dev)");
    }

    @Test
    @DisplayName("log master 显示 master 可达的提交")
    void log_withRevision_master(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "log", "master", "--oneline");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("second").contains("first");
    }

    @Test
    @DisplayName("log 单 revision 显示该起点可达的提交")
    void log_singleRevision_showsReachable(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "dev");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "dev");
        Path f = tempDir.resolve("f.txt");
        Files.write(f, "v3".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "third");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "log", "dev", "--oneline");
        assertThat(result.getExitCode()).isEqualTo(0);
        String out = result.getOutput();
        assertThat(out).contains("third").contains("second").contains("first");
    }

    @Test
    @DisplayName("log A..B 只显示在 B 可达且 A 不可达的提交")
    void log_dotDot_onlyCommitsInBNotInA(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "dev");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "dev");
        Path f = tempDir.resolve("f.txt");
        Files.write(f, "v3".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "third");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "log", "master..dev", "--oneline");
        assertThat(result.getExitCode()).isEqualTo(0);
        String out = result.getOutput();
        assertThat(out).contains("third").doesNotContain("second");
    }

    @Test
    @DisplayName("log 非法 revision 失败并输出 fatal")
    void log_invalidRevision_fails(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "log", "nonexistent-branch");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("fatal:");
    }
}

