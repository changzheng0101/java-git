package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.JitTestUtil;
import com.weixiao.JitTestUtil.ExecuteResult;
import com.weixiao.obj.Commit;
import com.weixiao.repo.ObjectDatabase;
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

    /**
     * 初始化分支结构：
     *
     * 文本示意图（提交沿时间向下）：
     *
     *   master:  first ----> second
     *                        |
     *                        +----> (branch) dev
     *                                  |
     *                                  v
     *                                 third
     *
     * - 在 master 上依次提交 first、second；
     * - 从 second 创建分支 dev 并 checkout；
     * - 在 dev 上再提交 third。
     * 结果：
     * - master: first -> second
     * - dev:    first -> second -> third
     */
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
     *
     * 文本示意图（merge 前）：
     *
     *   master:  A --- B
     *                 ^
     *                 |
     *               (fork)
     *                 |
     *   dev:          B --- C
     *
     * - A=first, B=second（master 上的两个提交）；
     * - C=third，是在从 B fork 出来的 dev 上再提交一次；
     * - 在 dev 上执行 merge master，相当于把 master 分支 fast-forward 合并进 dev，
     *   当前实现会写出一个新的 merge commit（父为 [devTip, masterTip]）。
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
     *
     * 文本示意图（merge 前）：
     *
     *   master:  A --- B
     *                 ^
     *                 |
     *               (fork)
     *                 |
     *   dev:          B --- C
     *
     * merge 之后：
     *
     *   master:  A --- B
     *                 ^
     *                 |
     *               (fork)
     *                 |
     *   dev:          B --- C --- M
     *                        ^   ^
     *                        |   |
     *                     parents=[C (dev tip), B (master tip)]
     *
     * 该用例断言：
     * - HEAD 从 C 移到新提交 M；
     * - M 有两个 parent，且顺序为 [devTip, masterTip]。
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
     *
     * 文本示意图：
     *
     *   master:  A --- B   (HEAD -> B)
     *
     * 在 B 上执行：
     *
     *   jit merge master
     *
     * 合并后得到：
     *
     *   master:  A --- B --- M
     *                    ^   ^
     *                    |   |
     *              parents=[B, B]
     *
     * 即使合并目标与当前分支 tip 相同，也会生成一个新的 merge commit，
     * 且该 commit 有两个相同的 parent，HEAD 移动到 M。
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

    /**
     * 场景：菱形提交图，测试 findBca 在多 parent 情况下返回预期的单一 BCA。
     *
     * 文本示意图：
     *
     *        A
     *       / \
     *      B   C
     *       \ /
     *        D
     *
     * - A 为根提交（无 parent）；
     * - B 和 C 都以 A 为 parent；
     * - D 是一个 merge commit，parents=[B, C]。
     *
     * 期望：
     * - findBca(B, C) = A
     * - findBca(B, D) = B
     * - findBca(C, D) = C
     */
    @Test
    @DisplayName("findBca 在菱形提交图下返回预期 BCA")
    void findBca_diamondGraph_returnsExpectedAncestors(@TempDir Path tempDir) throws Exception {
        // 初始化空仓库，方便直接往对象库写入 commit
        JIT.execute("-C", tempDir.toString(), "init");
        Repository.find(tempDir);
        ObjectDatabase db = Repository.INSTANCE.getDatabase();

        // A: root commit（无 parent）
        Commit a = new Commit("treeA", java.util.Collections.<String>emptyList(), "author", "committer", "A");
        String aOid = db.store(a);

        // B: parent = A
        Commit b = new Commit("treeB", java.util.Collections.singletonList(aOid), "author", "committer", "B");
        String bOid = db.store(b);

        // C: parent = A
        Commit c = new Commit("treeC", java.util.Collections.singletonList(aOid), "author", "committer", "C");
        String cOid = db.store(c);

        // D: merge commit, parents = [B, C]
        Commit d = new Commit("treeD", java.util.Arrays.asList(bOid, cOid), "author", "committer", "D");
        String dOid = db.store(d);

        // B 与 C 的 BCA 是 A
        assertThat(MergeCommand.findBca(bOid, cOid)).isEqualTo(aOid);

        // B 与 D 的 BCA 是 B
        assertThat(MergeCommand.findBca(bOid, dOid)).isEqualTo(bOid);

        // C 与 D 的 BCA 是 C
        assertThat(MergeCommand.findBca(cOid, dOid)).isEqualTo(cOid);
    }

    /**
     * 场景：多个共同祖先时，只保留“最低”的 best common ancestor。
     *
     * 文本示意图：
     *
     *        A
     *       / \
     *      B   C
     *       \ /
     *        E
     *        |
     *        F
     *
     * - A 为根；
     * - B、C 均来自 A；
     * - E 是 B 与 C 的 merge commit（parents=[B, C]）；
     * - F 基于 E 再提交一次。
     *
     * 对于 (B, F)：
     * - 共同祖先有 A 和 B；
     * - best common ancestor 应为 B（A 是 B 的祖先，应被过滤掉）。
     */
    @Test
    @DisplayName("findBca 在多共同祖先场景下只返回最低的 BCA")
    void findBca_multipleCommonAncestors_returnsLowest(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Repository.find(tempDir);
        ObjectDatabase db = Repository.INSTANCE.getDatabase();

        // A: root
        Commit a = new Commit("treeA", java.util.Collections.<String>emptyList(), "author", "committer", "A");
        String aOid = db.store(a);

        // B: parent = A
        Commit b = new Commit("treeB", java.util.Collections.singletonList(aOid), "author", "committer", "B");
        String bOid = db.store(b);

        // C: parent = A
        Commit c = new Commit("treeC", java.util.Collections.singletonList(aOid), "author", "committer", "C");
        String cOid = db.store(c);

        // E: merge(B, C)
        Commit e = new Commit("treeE", java.util.Arrays.asList(bOid, cOid), "author", "committer", "E");
        String eOid = db.store(e);

        // F: parent = E
        Commit f = new Commit("treeF", java.util.Collections.singletonList(eOid), "author", "committer", "F");
        String fOid = db.store(f);

        // B 与 F 的共同祖先有 A 和 B，其中 best 应该是 B
        String bca = MergeCommand.findBca(bOid, fOid);
        assertThat(bca).isEqualTo(bOid);
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
