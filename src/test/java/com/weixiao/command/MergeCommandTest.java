package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.JitTestUtil;
import com.weixiao.JitTestUtil.ExecuteResult;
import com.weixiao.merge.CommonAncestors;
import com.weixiao.obj.Commit;
import com.weixiao.obj.TreeEntry;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Refs;
import com.weixiao.repo.Repository;
import com.weixiao.repo.SysRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MergeCommand 测试")
class MergeCommandTest {

    private static final CommandLine JIT = Jit.createCommandLine();

    private static void initRepoWithTwoCommits(Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path f = tempDir.resolve("f.txt");
        Files.writeString(f, "v1");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "first");

        Files.writeString(f, "v2");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "second");
    }

    /**
     * 初始化分支结构：
     * <p>
     * 文本示意图（提交沿时间向下）：
     * <p>
     *   master:  first ----> second
     *                        |
     *                        +----> (branch) dev
     *                                  |
     *                                  v
     *                                 third
     * <p>
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
        Files.writeString(f, "v3");
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
    void merge_noCommit_fails(@TempDir Path tempDir) {
        JIT.execute("-C", tempDir.toString(), "init");
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "master");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("Not a valid object name: 'HEAD'");
    }

    /**
     * 场景：线性历史下从 dev merge master（master 已是 dev 的祖先），无需合并与写新提交，应提示 Already up to date。
     * <p>
     * 文本示意图（merge 前）：
     * <p>
     *   master:  A --- B
     *                 ^
     *                 |
     *               (fork)
     *                 |
     *   dev:          B --- C
     * <p>
     * - A=first, B=second（master 上的两个提交）；
     * - C=third，是在从 B fork 出来的 dev 上再提交一次；
     * - 在 dev 上执行 merge master：被合并的提交已是 HEAD 的祖先，应直接返回 "Already up to date."。
     */
    @Test
    @DisplayName("被合并提交已是 HEAD 祖先时提示 Already up to date")
    void merge_linearHistory_printsAlreadyUpToDate(@TempDir Path tempDir) throws Exception {
        initRepoWithBranchAndExtraCommit(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "master");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Already up to date.");
    }

    /**
     * 场景：线性历史：dev 比 master 多一个提交，在 dev 上 merge master 不应写新 commit，HEAD 不变。
     * <p>
     * 文本示意图（merge 前）：
     * <p>
     *   master:  A --- B
     *                 ^
     *                 |
     *               (fork)
     *                 |
     *   dev:          B --- C
     * <p>
     * 该用例断言：
     * - 输出包含 "Already up to date."；
     * - HEAD 仍为 devTip。
     */
    @Test
    @DisplayName("线性历史下 merge 祖先提交不写新 commit 且 HEAD 不变")
    void merge_linearHistory_doesNotCreateCommitAndHeadUnchanged(@TempDir Path tempDir) throws Exception {
        initRepoWithBranchAndExtraCommit(tempDir);
        Repository.find(tempDir);
        String devTip = Repository.INSTANCE.getRefs().readHead();
        String masterTip = Repository.INSTANCE.getRefs().readRef(new SysRef(Refs.REFS_HEADS + "master"));
        assertThat(devTip).isNotEqualTo(masterTip);

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "master");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Already up to date.");

        String headAfterMerge = Repository.INSTANCE.getRefs().readHead();
        assertThat(headAfterMerge).isEqualTo(devTip);
    }

    /**
     * 场景：merge 目标与当前 HEAD 相同，不需要执行合并与写新提交，应提示 Already up to date。
     */
    @Test
    @DisplayName("merge 当前提交时提示 Already up to date 且 HEAD 不变")
    void merge_sameBranch_alreadyUpToDate(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        Repository.find(tempDir);
        String headOid = Repository.INSTANCE.getRefs().readHead();

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "master");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Already up to date.");

        String newHead = Repository.INSTANCE.getRefs().readHead();
        assertThat(newHead).isEqualTo(headOid);
    }

    /**
     * 场景：master 落后于 dev（dev 是 master 的后代），在 master 上 merge dev 应 fast-forward 到 dev tip，不写新 commit。
     */
    @Test
    @DisplayName("merge 后代提交时 fast-forward 更新 HEAD 且不写新 commit")
    void merge_descendant_fastForwards(@TempDir Path tempDir) throws Exception {
        initRepoWithBranchAndExtraCommit(tempDir);
        Repository.find(tempDir);

        // 当前在 dev，记录 dev tip，并切回 master
        String devTip = Repository.INSTANCE.getRefs().readHead();
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "master");
        String masterTip = Repository.INSTANCE.getRefs().readHead();
        assertThat(masterTip).isNotEqualTo(devTip);

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "dev");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getOutput()).contains("Fast-forward");

        String headAfter = Repository.INSTANCE.getRefs().readHead();
        assertThat(headAfter).isEqualTo(devTip);

        // fast-forward 后工作区内容应与 dev tip 一致：f.txt=v3
        Path f = tempDir.resolve("f.txt");
        String fContent = Files.readString(f);
        assertThat(fContent).isEqualTo("v3");
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
        Files.writeString(f, "1");
        Files.writeString(g, "1");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "g.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "A");

        // 从 A 创建 topic 分支
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "topic");

        // 在 master 上提交 B：修改 f.txt=2
        Files.writeString(f, "2");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "B");

        // 切换到 topic，并基于 A 提交 C：修改 g.txt=3
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "topic");
        Files.writeString(g, "3");
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
        assertThat(Files.exists(tempDir.resolve(".git").resolve("MERGE_HEAD"))).isFalse();
        assertThat(Files.exists(tempDir.resolve(".git").resolve("MERGE_MSG"))).isFalse();

        // 合并后工作区应同时包含两侧修改：f.txt=2，g.txt=3
        String fContent = Files.readString(f);
        String gContent = Files.readString(g);
        assertThat(fContent).isEqualTo("2");
        assertThat(gContent).isEqualTo("3");
    }

    /**
     * 场景：菱形提交图，测试 findBca 在多 parent 情况下返回预期的单一 BCA。
     * <p>
     * 文本示意图：
     * <p>
     *        A
     *       / \
     *      B   C
     *       \ /
     *        D
     * <p>
     * - A 为根提交（无 parent）；
     * - B 和 C 都以 A 为 parent；
     * - D 是一个 merge commit，parents=[B, C]。
     * <p>
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
        Commit a = new Commit("treeA", java.util.Collections.emptyList(), "author", "committer", "A");
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
        assertThat(CommonAncestors.findBestCommonAncestor(bOid, cOid)).isEqualTo(aOid);

        // B 与 D 的 BCA 是 B
        assertThat(CommonAncestors.findBestCommonAncestor(bOid, dOid)).isEqualTo(bOid);

        // C 与 D 的 BCA 是 C
        assertThat(CommonAncestors.findBestCommonAncestor(cOid, dOid)).isEqualTo(cOid);
    }

    /**
     * 场景：多个共同祖先时，只保留“最低”的 best common ancestor。
     * <p>
     * 文本示意图：
     * <p>
     *        A
     *       / \
     *      B   C
     *       \ /
     *        E
     *        |
     *        F
     * <p>
     * - A 为根；
     * - B、C 均来自 A；
     * - E 是 B 与 C 的 merge commit（parents=[B, C]）；
     * - F 基于 E 再提交一次。
     * <p>
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
        Commit a = new Commit("treeA", java.util.Collections.emptyList(), "author", "committer", "A");
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
        String bca = CommonAncestors.findBestCommonAncestor(bOid, fOid);
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

    /**
     * 场景：同一文件内容冲突（A=1，master 改为 2，topic 改为 3）。
     * <p>
     * 文本示意图：
     * <p>
     *        A(f.txt=1)
     *       /          \
     *   B(master=2)   C(topic=3)
     * <p>
     * 在 master 上执行 merge topic 后应进入冲突态：
     * - 不写新的 merge commit（HEAD 保持在 B）；
     * - 工作区 f.txt 写入冲突标记，且包含 2 和 3；
     * - index 中 f.txt 存在 stage 1/2/3 条目。
     */
    @Test
    @DisplayName("同一文件内容冲突时 merge 失败且写入冲突标记与冲突 stage")
    void merge_simpleContentConflict_marksConflictAndKeepsHead(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path f = tempDir.resolve("f.txt");

        // A: f.txt=1
        Files.writeString(f, "1");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "A");

        // 从 A 创建 topic
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "topic");

        // B(master): f.txt=2
        Files.writeString(f, "2");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "B");

        // C(topic): f.txt=3
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "topic");
        Files.writeString(f, "3");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "C");

        // 回到 master 执行 merge topic
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "master");
        Repository.find(tempDir);
        String headBeforeMerge = Repository.INSTANCE.getRefs().readHead();
        String topicTip = Repository.INSTANCE.getRefs().readRef(new SysRef(Refs.REFS_HEADS + "topic"));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "topic");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("merge conflicts detected");
        assertThat(result.getOutput()).contains("Auto-merging f.txt");
        assertThat(result.getOutput()).contains("CONFLICT (content): Merge conflict in f.txt");
        assertThat(Files.readString(tempDir.resolve(".git").resolve("MERGE_HEAD")).trim()).isEqualTo(topicTip);
        assertThat(Files.readString(tempDir.resolve(".git").resolve("MERGE_MSG")).trim()).isEqualTo("Merge branch 'topic'");

        String headAfterMerge = Repository.INSTANCE.getRefs().readHead();
        assertThat(headAfterMerge).isEqualTo(headBeforeMerge);

        String mergedContent = Files.readString(f);
        assertThat(mergedContent).contains("<<<<<<< HEAD");
        assertThat(mergedContent).contains("=======");
        assertThat(mergedContent).contains(">>>>>>> topic");
        assertThat(mergedContent).contains("2");
        assertThat(mergedContent).contains("3");

        Repository.INSTANCE.getIndex().load();
        assertThat(Repository.INSTANCE.getIndex().isConflicted()).isTrue();
        assertThat(Repository.INSTANCE.getIndex().getEntryForPath("f.txt", 1)).isNotNull();
        assertThat(Repository.INSTANCE.getIndex().getEntryForPath("f.txt", 2)).isNotNull();
        assertThat(Repository.INSTANCE.getIndex().getEntryForPath("f.txt", 3)).isNotNull();
    }

    /**
     * 场景：目录/文件名互斥冲突（D/F conflict）。
     * <p>
     * 文本示意图：
     * <p>
     *        A(emptyTree)
     *       /        \
     *   B(master):   C(topic):
     *   add f.txt    add f.txt/g.txt
     * <p>
     * 在 master 上 merge topic 后：
     * - 保留 f.txt/g.txt 到工作区和 index；
     * - 冲突路径 f.txt 以 f.txt~HEAD 写入工作区（不写入 index）；
     * - merge 失败且不写新 commit。
     */
    @Test
    @DisplayName("目录/文件名冲突时保留右侧路径并写入 f.txt~HEAD")
    void merge_pathTypeConflict_writesHeadRenamedFileAndKeepsRightPath(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");

        // A: 空提交（通过一个占位文件再删除保持流程简单）
        Path keep = tempDir.resolve("keep.txt");
        Files.writeString(keep, "k");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "keep.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "A");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "topic");

        // C(topic): add f.txt/g.txt
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "topic");
        Path dirF = tempDir.resolve("f.txt");
        Files.createDirectories(dirF);
        Path g = dirF.resolve("g.txt");
        Files.writeString(g, "right");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt/g.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "C");

        // 回到 master 后在 master 上提交 B：add f.txt
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "master");
        Path f = tempDir.resolve("f.txt");
        Files.writeString(f, "left");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "B");

        // 在 master merge topic
        Repository.find(tempDir);
        String headBeforeMerge = Repository.INSTANCE.getRefs().readHead();

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "topic");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("merge conflicts detected");
        assertThat(result.getOutput()).contains("CONFLICT (file/directory):");
        assertThat(result.getOutput()).contains("Adding f.txt as f.txt~HEAD");
        assertThat(Repository.INSTANCE.getRefs().readHead()).isEqualTo(headBeforeMerge);

        Path rightPath = tempDir.resolve("f.txt").resolve("g.txt");
        Path renamedHeadPath = tempDir.resolve("f.txt~HEAD");
        assertThat(Files.exists(rightPath)).isTrue();
        assertThat(Files.exists(renamedHeadPath)).isTrue();
        assertThat(Files.readString(rightPath)).isEqualTo("right");
        assertThat(Files.readString(renamedHeadPath)).isEqualTo("left");

        Repository.INSTANCE.getIndex().load();
        assertThat(Repository.INSTANCE.getIndex().isConflicted()).isTrue();
        assertThat(Repository.INSTANCE.getIndex().getEntryForPath("f.txt/g.txt", 0)).isNotNull();
        assertThat(Repository.INSTANCE.getIndex().getEntryForPath("f.txt~HEAD", 0)).isNull();
        assertThat(Repository.INSTANCE.getIndex().getEntryForPath("f.txt", 2)).isNotNull();
    }

    /**
     * 场景：目录/文件名互斥冲突（D/F conflict）反向用例。
     * <p>
     * 文本示意图：
     * <p>
     *        A(emptyTree)
     *       /        \
     *   B(master):   C(topic):
     *   add f.txt/g.txt  add f.txt
     * <p>
     * 在 master 上 merge topic 后：
     * - 保留 f.txt/g.txt 到工作区和 index；
     * - 右侧冲突路径 f.txt 以 f.txt~topic 写入工作区（不写入 index）；
     * - merge 失败且不写新 commit。
     */
    @Test
    @DisplayName("目录/文件名冲突反向场景会写入 f.txt~topic")
    void merge_pathTypeConflict_reverse_writesMergeSideRenamedFile(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");

        // A: 空提交（通过占位文件建立初始提交）
        Path keep = tempDir.resolve("keep.txt");
        Files.writeString(keep, "k");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "keep.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "A");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "topic");

        // B(master): add f.txt/g.txt
        Path dirF = tempDir.resolve("f.txt");
        Files.createDirectories(dirF);
        Path g = dirF.resolve("g.txt");
        Files.writeString(g, "left-dir");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt/g.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "B");

        // C(topic): add f.txt
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "topic");
        Path f = tempDir.resolve("f.txt");
        Files.writeString(f, "right-file");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "C");

        // 回到 master 执行 merge topic -> has bug
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "master");
        Repository.find(tempDir);
        String headBeforeMerge = Repository.INSTANCE.getRefs().readHead();

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "topic");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("merge conflicts detected");
        assertThat(result.getOutput()).contains("CONFLICT (file/directory):");
        assertThat(result.getOutput()).contains("Adding f.txt as f.txt~topic");
        assertThat(Repository.INSTANCE.getRefs().readHead()).isEqualTo(headBeforeMerge);

        Path keptPath = tempDir.resolve("f.txt").resolve("g.txt");
        Path renamedMergePath = tempDir.resolve("f.txt~topic");
        assertThat(Files.exists(keptPath)).isTrue();
        assertThat(Files.exists(renamedMergePath)).isTrue();
        assertThat(Files.readString(keptPath)).isEqualTo("left-dir");
        assertThat(Files.readString(renamedMergePath)).isEqualTo("right-file");

        Repository.INSTANCE.getIndex().load();
        assertThat(Repository.INSTANCE.getIndex().isConflicted()).isTrue();
        assertThat(Repository.INSTANCE.getIndex().getEntryForPath("f.txt/g.txt", 0)).isNotNull();
        assertThat(Repository.INSTANCE.getIndex().getEntryForPath("f.txt~topic", 0)).isNull();
        assertThat(Repository.INSTANCE.getIndex().getEntryForPath("f.txt", 3)).isNotNull();
    }

    @Test
    @DisplayName("merge 后若 index 仍处于冲突态则失败且不写新 commit")
    void merge_conflictedIndexAfterResolve_failsWithoutCommit(@TempDir Path tempDir) throws Exception {
        // 构造 A-B(master) 与 A-C(topic) 的非冲突分叉场景
        JIT.execute("-C", tempDir.toString(), "init");
        Path f = tempDir.resolve("f.txt");
        Path g = tempDir.resolve("g.txt");
        Files.writeString(f, "1");
        Files.writeString(g, "1");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "g.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "A");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "topic");

        Files.writeString(f, "2");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "B");

        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "topic");
        Files.writeString(g, "3");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "g.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "C");

        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "master");
        Repository.find(tempDir);
        String headBeforeMerge = Repository.INSTANCE.getRefs().readHead();

        // 预先向 index 写入一个与本次 merge 无关的冲突条目，模拟 resolve 后仍有冲突未解
        Repository.INSTANCE.getIndex().load();
        Repository.INSTANCE.getIndex().addConflictSet(
                "conflict.txt",
                new TreeEntry("100644", "conflict.txt", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                new TreeEntry("100644", "conflict.txt", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                null
        );
        Repository.INSTANCE.getIndex().save();

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "topic");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("merge conflicts detected");

        String headAfterMerge = Repository.INSTANCE.getRefs().readHead();
        assertThat(headAfterMerge).isEqualTo(headBeforeMerge);
    }

    /**
     * 场景：冲突后先保留 MERGE_*，手工解决并 add 后通过 merge --continue 完成提交。
     * <p>
     * 文本示意图：
     * <p>
     *        A(f.txt=1)
     *       /          \
     *   B(master=2)   C(topic=3)
     * <p>
     * 流程：
     * - 在 master merge topic 触发冲突，产生 MERGE_HEAD/MERGE_MSG；
     * - 手工把 f.txt 解决为 resolved，并 add 覆盖冲突条目；
     * - 执行 merge --continue，写入 merge commit，且清理 MERGE_*。
     */
    @Test
    @DisplayName("merge 冲突解决后可通过 merge --continue 写入 merge commit 并清理 MERGE_*")
    void merge_continue_afterResolvedConflicts_createsMergeCommit(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path f = tempDir.resolve("f.txt");

        Files.writeString(f, "1");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "A");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "topic");

        Files.writeString(f, "2");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "B");

        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "topic");
        Files.writeString(f, "3");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "C");

        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "master");
        Repository.find(tempDir);
        String headBeforeMerge = Repository.INSTANCE.getRefs().readHead();
        String topicTip = Repository.INSTANCE.getRefs().readRef(new SysRef(Refs.REFS_HEADS + "topic"));

        ExecuteResult mergeResult = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "topic");
        assertThat(mergeResult.getExitCode()).isNotEqualTo(0);
        assertThat(Files.exists(tempDir.resolve(".git").resolve("MERGE_HEAD"))).isTrue();
        assertThat(Files.exists(tempDir.resolve(".git").resolve("MERGE_MSG"))).isTrue();

        Files.writeString(f, "resolved");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");

        ExecuteResult continueResult = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "--continue");
        assertThat(continueResult.getExitCode()).isEqualTo(0);
        assertThat(continueResult.getOutput()).contains("Merge made. New commit:");
        assertThat(Files.exists(tempDir.resolve(".git").resolve("MERGE_HEAD"))).isFalse();
        assertThat(Files.exists(tempDir.resolve(".git").resolve("MERGE_MSG"))).isFalse();

        String headAfterContinue = Repository.INSTANCE.getRefs().readHead();
        assertThat(headAfterContinue).isNotEqualTo(headBeforeMerge);
        Commit mergeCommit = Repository.INSTANCE.getDatabase().loadCommit(headAfterContinue);
        assertThat(mergeCommit).isNotNull();
        assertThat(mergeCommit.getParentOids()).containsExactly(headBeforeMerge, topicTip);
        assertThat(mergeCommit.getMessage()).isEqualTo("Merge branch 'topic'");
    }

    /**
     * 场景：当存在未完成 merge（MERGE_HEAD 存在）时，禁止再次发起新的 merge。
     * <p>
     * 文本示意图：
     * <p>
     *        A(f.txt=1)
     *       /          \
     *   B(master=2)   C(topic=3)
     * <p>
     * 流程：
     * - 第一次 merge topic 进入冲突态（in-progress）；
     * - 再次执行 merge topic 应直接失败并提示 merge already in progress。
     */
    @Test
    @DisplayName("merge 进行中再次执行 merge <rev> 会失败并提示 in progress")
    void merge_whenInProgress_startNewMergeFails(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path f = tempDir.resolve("f.txt");

        Files.writeString(f, "1");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "A");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "topic");

        Files.writeString(f, "2");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "B");

        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "topic");
        Files.writeString(f, "3");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "C");

        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "master");
        ExecuteResult firstMerge = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "topic");
        assertThat(firstMerge.getExitCode()).isNotEqualTo(0);

        ExecuteResult secondMerge = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "topic");
        assertThat(secondMerge.getExitCode()).isNotEqualTo(0);
        assertThat(secondMerge.getErr()).contains("merge already in progress");
    }
}
