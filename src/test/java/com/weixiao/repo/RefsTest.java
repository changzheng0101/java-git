package com.weixiao.repo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Refs 测试")
class RefsTest {

    @Test
    @DisplayName("合法分支名：简单名、含 -/_、含 / 通过校验")
    void validateBranchName_validNames() {
        assertThat(Refs.validateBranchName("foo")).isNull();
        assertThat(Refs.validateBranchName("feature-foo")).isNull();
        assertThat(Refs.validateBranchName("feature_foo")).isNull();
        assertThat(Refs.validateBranchName("feature/bar")).isNull();
        assertThat(Refs.isValidBranchName("master")).isTrue();
    }

    @Test
    @DisplayName("非法分支名：空、..、@、/、//、. 结尾、. 开头段、.lock 结尾段")
    void validateBranchName_invalidNames() {
        assertThat(Refs.validateBranchName(null)).isNotNull();
        assertThat(Refs.validateBranchName("")).isNotNull();
        assertThat(Refs.validateBranchName("..")).isNotNull();
        assertThat(Refs.validateBranchName("a..b")).isNotNull();
        assertThat(Refs.validateBranchName("@")).isNotNull();
        assertThat(Refs.validateBranchName("/foo")).isNotNull();
        assertThat(Refs.validateBranchName("foo/")).isNotNull();
        assertThat(Refs.validateBranchName("foo//bar")).isNotNull();
        assertThat(Refs.validateBranchName("foo.")).isNotNull();
        assertThat(Refs.validateBranchName(".foo")).isNotNull();
        assertThat(Refs.validateBranchName("foo.lock")).isNotNull();
        assertThat(Refs.validateBranchName("refs/heads/x")).isNull(); // 合法名字，只是看起来像完整 ref
    }

    @Test
    @DisplayName("createBranch 写入 refs/heads/<name>，branchExists 与 readRef 一致")
    void createBranch_and_branchExists(@TempDir Path gitDir) throws Exception {
        Path refsHeads = gitDir.resolve("refs").resolve("heads");
        Files.createDirectories(refsHeads);
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/master\n");
        Files.writeString(gitDir.resolve("refs/heads/master"), "a".repeat(40) + "\n");

        Refs refs = new Refs(gitDir);
        assertThat(refs.branchExists("new")).isFalse();
        refs.createBranch("new", "b".repeat(40));
        assertThat(refs.branchExists("new")).isTrue();
        assertThat(refs.readRef(new SysRef("refs/heads/new"))).isEqualTo("b".repeat(40));
    }
}
