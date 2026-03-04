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

@DisplayName("BranchCommand 测试")
class BranchCommandTest {

    private static final CommandLine JIT = Jit.createCommandLine();

    /** 在给定目录创建仓库并做一次 commit，便于 branch 测试。 */
    private static void initRepoWithOneCommit(Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path f = tempDir.resolve("f.txt");
        Files.write(f, "x".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "first");
    }

    @Test
    @DisplayName("非仓库目录执行 branch 失败")
    void branch_outsideRepo_fails(@TempDir Path dir) {
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "branch", "foo");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("not a jit repository");
    }

    @Test
    @DisplayName("无 commit 时 branch 失败并提示 Not a valid object name")
    void branch_noCommit_fails(@TempDir Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "foo");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("Not a valid object name");
    }

    @Test
    @DisplayName("合法分支名：简单名创建成功")
    void branch_validSimpleName_succeeds(@TempDir Path tempDir) throws Exception {
        initRepoWithOneCommit(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "foo");
        assertThat(result.getExitCode()).isEqualTo(0);
        Repository repo = Repository.find(tempDir);
        SysRef fooRef = new SysRef(Refs.REFS_HEADS + "foo");
        assertThat(repo.getRefs().readRef(fooRef)).isNotNull();
        assertThat(repo.getRefs().readRef(fooRef)).isEqualTo(repo.getRefs().readHead());
    }

    @Test
    @DisplayName("合法分支名：含连字符创建成功")
    void branch_validWithHyphen_succeeds(@TempDir Path tempDir) throws Exception {
        initRepoWithOneCommit(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "feature-foo");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(Repository.find(tempDir).getRefs().branchExists("feature-foo")).isTrue();
    }

    @Test
    @DisplayName("合法分支名：含下划线创建成功")
    void branch_validWithUnderscore_succeeds(@TempDir Path tempDir) throws Exception {
        initRepoWithOneCommit(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "feature_foo");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(Repository.find(tempDir).getRefs().branchExists("feature_foo")).isTrue();
    }

    @Test
    @DisplayName("合法分支名：含斜杠（如 feature/bar）创建成功")
    void branch_validWithSlash_succeeds(@TempDir Path tempDir) throws Exception {
        initRepoWithOneCommit(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "feature/bar");
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(Repository.find(tempDir).getRefs().readRef(new SysRef(Refs.REFS_HEADS + "feature/bar"))).isNotNull();
    }


    @Test
    @DisplayName("非法分支名：含 .. 失败")
    void branch_invalidDoubleDot_fails(@TempDir Path tempDir) throws Exception {
        initRepoWithOneCommit(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "a..b");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("not a valid branch name").contains("..");
    }

    @Test
    @DisplayName("非法分支名：单独 @ 失败")
    void branch_invalidAtOnly_fails(@TempDir Path tempDir) throws Exception {
        initRepoWithOneCommit(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "@");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("not a valid branch name");
    }

    @Test
    @DisplayName("非法分支名：以 / 开头失败")
    void branch_invalidLeadingSlash_fails(@TempDir Path tempDir) throws Exception {
        initRepoWithOneCommit(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "/foo");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("not a valid branch name");
    }

    @Test
    @DisplayName("非法分支名：以 / 结尾失败")
    void branch_invalidTrailingSlash_fails(@TempDir Path tempDir) throws Exception {
        initRepoWithOneCommit(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "foo/");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("not a valid branch name");
    }

    @Test
    @DisplayName("非法分支名：段以 .lock 结尾失败")
    void branch_invalidSegmentEndsWithLock_fails(@TempDir Path tempDir) throws Exception {
        initRepoWithOneCommit(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "foo.lock");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("not a valid branch name").contains("lock");
    }

    @Test
    @DisplayName("非法分支名：段以 . 开头失败")
    void branch_invalidSegmentStartsWithDot_fails(@TempDir Path tempDir) throws Exception {
        initRepoWithOneCommit(tempDir);
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", ".foo");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("not a valid branch name");
    }

    @Test
    @DisplayName("分支已存在时失败")
    void branch_alreadyExists_fails(@TempDir Path tempDir) throws Exception {
        initRepoWithOneCommit(tempDir);
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "dup");
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "dup");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("already exists");
    }

    @Test
    @DisplayName("无参数时按字母顺序列出分支，当前分支前加 *")
    void branch_listBranches_basic(@TempDir Path tempDir) throws Exception {
        initRepoWithOneCommit(tempDir);
        // 创建额外分支
        ExecuteResult createFoo = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "foo");
        assertThat(createFoo.getExitCode()).isEqualTo(0);

        ExecuteResult list = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch");
        assertThat(list.getExitCode()).isEqualTo(0);
        String out = list.getOutput();
        // master 为当前分支，foo 为普通分支
        assertThat(out).contains("* master");
        assertThat(out).contains("  foo").doesNotContain("* foo");
    }

    @Test
    @DisplayName("branch --verbose 时输出分支名、缩写 oid 和提交标题")
    void branch_listBranches_verbose(@TempDir Path tempDir) throws Exception {
        initRepoWithOneCommit(tempDir);
        ExecuteResult list = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "--verbose");
        assertThat(list.getExitCode()).isEqualTo(0);
        String out = list.getOutput();
        // 应包含 master、7 位十六进制 oid 以及提交信息 "first"
        assertThat(out).contains("master");
        assertThat(out).contains("first");
    }
}
