package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.JitTestUtil;
import com.weixiao.JitTestUtil.ExecuteResult;
import com.weixiao.obj.Commit;
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

    /**
     * 场景：线性历史下从 dev merge master，命令成功并输出 "Merge made"，产生新的 merge commit。
     */
    @Test
    @DisplayName("线性历史下 merge 另一分支成功并输出 Merge made")
    void merge_linearHistory_printsMergeMade(@TempDir Path tempDir) throws Exception {
        initRepoWithBranchAndExtraCommit(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "master");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Merge made.");
    }

    /**
     * 场景：在 dev 上执行 merge master（线性历史：dev 比 master 多一个提交），产生一个双 parent 的 merge commit，
     * 第一个 parent 为当前 HEAD（dev 的 tip），第二个为被合并分支（master）的 tip，且 HEAD 更新为该新 commit。
     */
    @Test
    @DisplayName("merge 产生双 parent commit 且 HEAD 指向新 commit")
    void merge_linearHistory_createsTwoParentCommitAndUpdatesHead(@TempDir Path tempDir) throws Exception {
        initRepoWithBranchAndExtraCommit(tempDir);
        Repository.find(tempDir);
        String devTip = Repository.INSTANCE.getRefs().readHead();
        String masterTip = Repository.INSTANCE.getRefs().readRef(new SysRef(Refs.REFS_HEADS + "master"));
        assertThat(devTip).isNotEqualTo(masterTip);

        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "master");

        String headAfterMerge = Repository.INSTANCE.getRefs().readHead();
        assertThat(headAfterMerge).isNotEqualTo(devTip).isNotEqualTo(masterTip);

        Commit mergeCommit = Repository.INSTANCE.getDatabase().loadCommit(headAfterMerge);
        assertThat(mergeCommit).isNotNull();
        assertThat(mergeCommit.getParentOids()).hasSize(2);
        assertThat(mergeCommit.getParentOids()).containsExactly(devTip, masterTip);
        assertThat(mergeCommit.getMessage()).contains("Merge branch");
    }

    /**
     * 场景：当前已在 master 上，执行 merge master（HEAD 与参数指向同一 commit），仍会创建一个 merge commit（双 parent 均为同一 oid），HEAD 指向新 commit。
     */
    @Test
    @DisplayName("merge 当前分支时仍创建 merge commit 并更新 HEAD")
    void merge_sameBranch_createsMergeCommit(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        Repository.find(tempDir);
        String headOid = Repository.INSTANCE.getRefs().readHead();

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "master");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Merge made.");

        String newHead = Repository.INSTANCE.getRefs().readHead();
        assertThat(newHead).isNotEqualTo(headOid);
        Commit mergeCommit = Repository.INSTANCE.getDatabase().loadCommit(newHead);
        assertThat(mergeCommit.getParentOids()).hasSize(2);
        assertThat(mergeCommit.getParentOids()).containsExactly(headOid, headOid);
    }

    /**
     * 场景：The root commit A contains two files, f.txt and g.txt, both containing the value 1。
     * 在 master 上基于 A 提交 B，将 f.txt 的内容改为 2；在 topic 分支上也基于 A 提交 C，将 g.txt 的内容改为 3。
     * 在 master 上执行 merge topic 后，不存在冲突，产生一个新的 merge commit（parents=[B,C]），
     * 合并结果应同时包含两侧的修改：f.txt=2，g.txt=3。
     */
    @Test
    @DisplayName("非冲突分支合并：一侧改 f.txt，另一侧改 g.txt，merge 后两侧修改都保留")
    void merge_nonConflictingBranches_preservesBothSidesChanges(@TempDir Path tempDir) throws Exception {
        // 初始化仓库并创建根提交 A：f.txt=1, g.txt=1
        JIT.execute("-C", tempDir.toString(), "init");
        Path f = tempDir.resolve("f.txt");
        Path g = tempDir.resolve("g.txt");
        Files.write(f, "1".getBytes(StandardCharsets.UTF_8));
        Files.write(g, "1".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "g.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "A");

        // 从 A 创建 topic 分支
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "topic");

        // 在 master 上提交 B：修改 f.txt=2
        Files.write(f, "2".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "B");

        // 切换到 topic，并基于 A 提交 C：修改 g.txt=3
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "topic");
        Files.write(g, "3".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "g.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "C");

        // 回到 master，在 master 上执行 merge topic 之前，记录两侧分支的 tip（B, C）
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "master");
        Repository.find(tempDir);
        String masterTipBeforeMerge = Repository.INSTANCE.getRefs().readRef(new SysRef(Refs.REFS_HEADS + "master"));
        String topicTipBeforeMerge = Repository.INSTANCE.getRefs().readRef(new SysRef(Refs.REFS_HEADS + "topic"));

        // 在 master 上执行 merge topic
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "topic");

        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Merge made.");

        // merge 必须创建一个新的 commit，且其 parents 为 [masterTipBeforeMerge, topicTipBeforeMerge]
        String headAfterMerge = Repository.INSTANCE.getRefs().readHead();
        assertThat(headAfterMerge)
                .isNotNull()
                .isNotEqualTo(masterTipBeforeMerge)
                .isNotEqualTo(topicTipBeforeMerge);
        Commit mergeCommit = Repository.INSTANCE.getDatabase().loadCommit(headAfterMerge);
        assertThat(mergeCommit).isNotNull();
        assertThat(mergeCommit.getParentOids()).hasSize(2);
        assertThat(mergeCommit.getParentOids()).containsExactly(masterTipBeforeMerge, topicTipBeforeMerge);

        // 合并后工作区应同时包含两侧修改：f.txt=2，g.txt=3
        String fContent = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        String gContent = new String(Files.readAllBytes(g), StandardCharsets.UTF_8);
        assertThat(fContent).isEqualTo("2");
        assertThat(gContent).isEqualTo("3");
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
