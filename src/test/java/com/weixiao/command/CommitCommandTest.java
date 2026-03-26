package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.JitTestUtil;
import com.weixiao.JitTestUtil.ExecuteResult;
import com.weixiao.obj.Commit;
import com.weixiao.repo.Refs;
import com.weixiao.repo.Repository;
import com.weixiao.repo.SysRef;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommitCommand 测试")
class CommitCommandTest {

    private static final CommandLine JIT = Jit.createCommandLine();

    /**
     * 在无 .git 的目录执行 jit commit 时应失败，并在 stderr 中提示 not a jit repository。
     * 示例：对空目录 /tmp/emptyTree 执行 "jit commit -m msg /tmp/emptyTree" → 退出码非 0，stderr 含 "not a jit repository"。
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
    void commit_emptyIndex_fails(@TempDir Path tempDir) {
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
        Files.writeString(f, "hello");
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
     * <p>
     * 文本示意图（工作区路径结构）：
     * <p>
     *   工作区：
     *     dir1/
     *       file1.txt      ("content1")
     *       subdir/
     *         file2.txt    ("content2")
     *     root.txt         ("root content")
     * <p>
     * 操作：
     *   - jit add dir1 root.txt
     *   - jit commit -m "nested commit"
     * <p>
     * 期望：
     *   - 从 index 构建出的树包含一层目录 dir1 以及其子目录 subdir；
     *   - 提交对象存在于对象库中，HEAD 指向该 commit。
     */
    @Test
    @DisplayName("add 嵌套目录后 commit 支持嵌套 tree 结构")
    void commit_withNestedDirectories_succeeds(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");

        Path dir1 = tempDir.resolve("dir1");
        Files.createDirectories(dir1);
        Files.writeString(dir1.resolve("file1.txt"), "content1");

        Path subdir = dir1.resolve("subdir");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve("file2.txt"), "content2");

        Files.writeString(tempDir.resolve("root.txt"), "root content");

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
        com.weixiao.obj.GitObject commitObj = repo.getDatabase().load(head);
        assertThat(commitObj.getType()).isEqualTo("commit");
    }

    /**
     * 在多层嵌套目录中 add 后再 commit，验证从 index 构建的 tree 正确。
     * <p>
     * 文本示意图（工作区路径结构）：
     * <p>
     *   工作区：
     *     a/
     *       b/
     *         c/
     *           d/
     *             file.txt  ("deep content")
     * <p>
     * 操作：
     *   - jit add a
     *   - jit commit -m "deep nested"
     * <p>
     * 期望：
     *   - 从 index 构建出的树包含 a/b/c/d 四级目录；
     *   - 提交写入对象库，HEAD 指向新 commit。
     */
    @Test
    @DisplayName("add 深层嵌套目录后 commit 成功")
    void commit_withDeepNestedDirectories_succeeds(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path deepDir = tempDir.resolve("a").resolve("b").resolve("c").resolve("d");
        Files.createDirectories(deepDir);
        Files.writeString(deepDir.resolve("file.txt"), "deep content");

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

    /**
     * 场景：merge 冲突未解决时执行 commit，应按 continue 语义失败。
     * <p>
     * 文本示意图：
     * <p>
     *        A(f.txt=1)
     *       /          \
     *   B(master=2)   C(topic=3)
     * <p>
     * 流程：
     * - 在 master merge topic 触发冲突，产生 MERGE_HEAD/MERGE_MSG；
     * - 不解决冲突直接执行 commit；
     * <p>
     * 期望：
     * - commit 失败并提示 unresolved conflicts；
     * - MERGE_HEAD/MERGE_MSG 仍然保留。
     */
    @Test
    @DisplayName("merge 冲突未解决时 commit 失败并保留 MERGE_*")
    void commit_inProgressMergeWithConflicts_fails(@TempDir Path tempDir) throws Exception {
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
        ExecuteResult mergeResult = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "merge", "topic");
        assertThat(mergeResult.getExitCode()).isNotEqualTo(0);

        ExecuteResult commitResult = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit");
        assertThat(commitResult.getExitCode()).isNotEqualTo(0);
        assertThat(commitResult.getErr()).contains("unresolved conflicts remain");
        assertThat(Files.exists(tempDir.resolve(".git").resolve("MERGE_HEAD"))).isTrue();
        assertThat(Files.exists(tempDir.resolve(".git").resolve("MERGE_MSG"))).isTrue();
    }

    /**
     * 场景：merge 冲突手工解决并 add 后，执行 commit 应按 continue 语义完成 merge 提交。
     * <p>
     * 文本示意图：
     * <p>
     *        A(f.txt=1)
     *       /          \
     *   B(master=2)   C(topic=3)
     * <p>
     * 流程：
     * - 在 master merge topic 触发冲突，产生 MERGE_HEAD/MERGE_MSG；
     * - 手工把 f.txt 改为 resolved 并 add；
     * - 执行 commit（不传 -m）；
     * <p>
     * 期望：
     * - 写入 merge commit，parents=[masterTip, topicTip]；
     * - 提交消息使用 MERGE_MSG；
     * - MERGE_HEAD/MERGE_MSG 被清理。
     */
    @Test
    @DisplayName("merge 冲突解决后 commit 可继续并写入 merge commit")
    void commit_inProgressMergeResolved_succeeds(@TempDir Path tempDir) throws Exception {
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

        Files.writeString(f, "resolved");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");

        ExecuteResult commitResult = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit");
        assertThat(commitResult.getExitCode()).isEqualTo(0);
        assertThat(commitResult.getOutput()).contains("Merge made. New commit:");
        assertThat(Files.exists(tempDir.resolve(".git").resolve("MERGE_HEAD"))).isFalse();
        assertThat(Files.exists(tempDir.resolve(".git").resolve("MERGE_MSG"))).isFalse();

        String headAfterCommit = Repository.INSTANCE.getRefs().readHead();
        Commit mergeCommit = Repository.INSTANCE.getDatabase().loadCommit(headAfterCommit);
        Assertions.assertNotNull(mergeCommit);
        assertThat(mergeCommit.getParentOids()).containsExactly(headBeforeMerge, topicTip);
        assertThat(mergeCommit.getMessage()).isEqualTo("Merge branch 'topic'");
    }
}
