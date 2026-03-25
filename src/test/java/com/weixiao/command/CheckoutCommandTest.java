package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.JitTestUtil;
import com.weixiao.JitTestUtil.ExecuteResult;
import com.weixiao.obj.Commit;
import com.weixiao.obj.GitObject;
import com.weixiao.repo.Refs;
import com.weixiao.repo.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CheckoutCommand 测试")
class CheckoutCommandTest {

    private static final CommandLine JIT = Jit.createCommandLine();

    /**
     * 在给定目录创建仓库并做一次 commit，便于 checkout/branch 测试。
     *
     * 文本示意图：
     *
     *   master: A  (A 的提交消息由参数 message 决定)
     *
     * - init 后写入 f.txt="v1"，add 并 commit -m message；
     * - HEAD -> master，master -> A，返回 A 的 oid。
     */
    private static String initRepoWithOneCommit(Path tempDir, String message) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path f = tempDir.resolve("f.txt");
        Files.writeString(f, "v1");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        ExecuteResult commit = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", message);
        assertThat(commit.getExitCode()).as("commit out: %s err: %s", commit.getOutput(), commit.getErr()).isEqualTo(0);
        Repository repo = Repository.find(tempDir);
        assertThat(repo).isNotNull();
        return repo.getRefs().readHead();
    }

    private static String readHeadFileRaw(Path tempDir) throws Exception {
        Path head = tempDir.resolve(".git").resolve("HEAD");
        return new String(Files.readAllBytes(head), StandardCharsets.UTF_8).trim();
    }

    @Test
    @DisplayName("在非仓库目录执行 checkout 失败并提示 not a jit repository")
    void checkout_outsideRepo_fails(@TempDir Path dir) {
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "checkout", "master");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("not a jit repository");
    }

    @Test
    @DisplayName("无效 ref 时 checkout 失败并提示 fatal")
    void checkout_invalidRef_fails(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "nonexistent-branch");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("fatal:");
    }

    @Test
    @DisplayName("已有两个 commit 时 checkout 到前一个 commit，工作区和 HEAD 变为目标 commit 状态")
    void checkout_toPreviousCommit_updatesWorkspaceAndHead(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");

        Path a = tempDir.resolve("a.txt");
        Files.writeString(a, "v1");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "a.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "first");

        Files.writeString(a, "v2");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "a.txt");
        ExecuteResult secondCommit = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "second");
        assertThat(secondCommit.getExitCode()).isEqualTo(0);

        /**
         * 文本示意图：
         *
         *   master:  A --- B
         *             ^     ^
         *             |     |
         *          "first" "second"
         *
         * - 当前 HEAD 在 B（a.txt="v2"）；
         * - find parent(B)=A 作为 firstCommitOid；
         * - checkout firstCommitOid 后，HEAD 应指向 A，工作区 a.txt 回到 "v1"。
         */
        Repository repo = Repository.find(tempDir);
        String headAfterSecond = repo.getRefs().readHead();
        GitObject headObj = repo.getDatabase().load(headAfterSecond);
        String firstCommitOid = ((Commit) headObj).getParentOid();

        ExecuteResult checkoutResult = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", firstCommitOid);
        assertThat(checkoutResult.getExitCode()).as("checkout err: %s", checkoutResult.getErr()).isEqualTo(0);

        repo = Repository.find(tempDir);
        assertThat(repo.getRefs().readHead()).isEqualTo(firstCommitOid);
        assertThat(new String(Files.readAllBytes(a), StandardCharsets.UTF_8)).isEqualTo("v1");
    }

    @Test
    @DisplayName("checkout 到当前 HEAD 时无变更")
    void checkout_toCurrentHead_noChange(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path f = tempDir.resolve("f.txt");
        Files.writeString(f, "content");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "only");

        Repository repo = Repository.find(tempDir);
        String headOid = repo.getRefs().readHead();

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", headOid);
        assertThat(result.getExitCode()).isEqualTo(0);
        Repository repoAfter = Repository.find(tempDir);
        assertThat(repoAfter.getRefs().readHead()).isEqualTo(headOid);
    }

    @Test
    @DisplayName("checkout 到 commit（detached HEAD）输出与 git 一致，且 HEAD 文件写为 commit id")
    void checkout_toCommit_detachedHead_printsHeadIsNowAt(@TempDir Path tempDir) throws Exception {
        String headOid = initRepoWithOneCommit(tempDir, "only commit");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", headOid);
        assertThat(result.getExitCode()).as("checkout out: %s err: %s", result.getOutput(), result.getErr()).isEqualTo(0);
        assertThat(result.getOutput()).contains("HEAD is now at " + headOid.substring(0, 7) + " only commit");

        String headRaw = readHeadFileRaw(tempDir);
        assertThat(headRaw).isEqualTo(headOid);
        assertThat(Repository.find(tempDir).getRefs().getHeadRef()).isNull();
    }

    @Test
    @DisplayName("checkout 到当前分支输出 Already on '<branch>'（与 git 一致）")
    void checkout_toSameBranch_printsAlreadyOn(@TempDir Path tempDir) throws Exception {
        initRepoWithOneCommit(tempDir, "first");

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "master");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Already on 'master'");
        assertThat(readHeadFileRaw(tempDir)).contains("ref: " + Refs.REFS_HEADS + "master");
    }


    @Test
    @DisplayName("从 detached HEAD checkout 回分支：输出 switched，并将 HEAD 从 oid 改回 symref")
    void checkout_fromDetachedToBranch_restoresSymref(@TempDir Path tempDir) throws Exception {
        String headOid = initRepoWithOneCommit(tempDir, "first");

        ExecuteResult detached = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", headOid);
        assertThat(detached.getExitCode()).isEqualTo(0);
        assertThat(readHeadFileRaw(tempDir)).isEqualTo(headOid);

        ExecuteResult back = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "master");
        assertThat(back.getExitCode()).isEqualTo(0);
        assertThat(back.getOutput()).contains("Switched to branch 'master'");
        assertThat(readHeadFileRaw(tempDir)).contains("ref: " + Refs.REFS_HEADS + "master");
    }
}
