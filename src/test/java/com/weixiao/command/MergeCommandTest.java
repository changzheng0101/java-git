package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.JitTestUtil;
import com.weixiao.JitTestUtil.ExecuteResult;
import com.weixiao.repo.Refs;
import com.weixiao.repo.Repository;
import com.weixiao.repo.SysRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MergeCommand 测试")
class MergeCommandTest {

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

    /** master: first→second；创建 dev 并 checkout，提交 third。dev: third→second→first，master: second→first。 */
    private static void initRepoWithBranchAndExtraCommit(Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "dev");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "dev");
        Path f = tempDir.resolve("f.txt");
        Files.write(f, "v3".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "third");
    }

    @Test
    @DisplayName("在非仓库目录执行 merge 失败")
    void merge_outsideRepo_fails(@TempDir Path dir) {
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "merge", "master");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("not a jit repository");
    }

    @Test
    @DisplayName("无提交时 merge 失败并提示 HEAD 无效")
    void merge_noCommit_fails(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "master");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("Not a valid object name: 'HEAD'");
    }

    @Test
    @DisplayName("线性历史下 merge 另一分支输出 BCA 为共同祖先 commit id")
    void merge_linearHistory_printsBca(@TempDir Path tempDir) throws Exception {
        initRepoWithBranchAndExtraCommit(tempDir);
        Repository.find(tempDir);
        String secondOid = Repository.INSTANCE.getRefs().readRef(new SysRef(Refs.REFS_HEADS + "master"));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "master");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Best common ancestor: " + secondOid);
    }

    @Test
    @DisplayName("merge 当前分支（HEAD 与参数指向同一 commit）时 BCA 即该 commit")
    void merge_sameBranch_bcaIsHead(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        Repository.find(tempDir);
        String headOid = Repository.INSTANCE.getRefs().readHead();

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "master");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Best common ancestor: " + headOid);
    }

    @Test
    @DisplayName("merge 非法 revision 失败并输出 fatal")
    void merge_invalidRevision_fails(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "nonexistent-branch");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("fatal:");
    }
}
