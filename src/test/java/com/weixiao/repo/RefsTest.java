package com.weixiao.repo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    @DisplayName("deleteBranch 存在分支时返回 oid 并删除 ref 文件")
    void deleteBranch_existing_returnsOidAndRemovesFile(@TempDir Path gitDir) throws Exception {
        Path refsHeads = gitDir.resolve("refs").resolve("heads");
        Files.createDirectories(refsHeads);
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/master\n");
        String oid = "a".repeat(40);
        Files.writeString(refsHeads.resolve("toDel"), oid + "\n");

        Refs refs = new Refs(gitDir);
        assertThat(refs.branchExists("toDel")).isTrue();
        String returned = refs.deleteBranch("toDel");
        assertThat(returned).isEqualTo(oid);
        assertThat(refs.branchExists("toDel")).isFalse();
        assertThat(Files.exists(refsHeads.resolve("toDel"))).isFalse();
    }

    @Test
    @DisplayName("deleteBranch 分支不存在时抛出 IOException")
    void deleteBranch_notFound_throws(@TempDir Path gitDir) throws Exception {
        Path refsHeads = gitDir.resolve("refs").resolve("heads");
        Files.createDirectories(refsHeads);
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/master\n");

        Refs refs = new Refs(gitDir);
        assertThrows(IOException.class, () -> refs.deleteBranch("nonexistent"));
    }

    @Test
    @DisplayName("deleteBranch 空分支名时抛出")
    void deleteBranch_emptyName_throws(@TempDir Path gitDir) throws Exception {
        Files.createDirectories(gitDir.resolve("refs").resolve("heads"));
        Refs refs = new Refs(gitDir);
        assertThrows(IOException.class, () -> refs.deleteBranch(""));
    }

    @Test
    @DisplayName("deleteBranch 嵌套分支删除后空父目录被清理，refs/heads 保留")
    void deleteBranch_nestedBranch_removesEmptyParentDir(@TempDir Path gitDir) throws Exception {
        Path headsDir = gitDir.resolve("refs").resolve("heads");
        Path featureDir = headsDir.resolve("feature");
        Files.createDirectories(featureDir);
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/master\n");
        Files.writeString(featureDir.resolve("bar"), "b".repeat(40) + "\n");

        Refs refs = new Refs(gitDir);
        refs.deleteBranch("feature/bar");
        assertThat(Files.exists(featureDir.resolve("bar"))).isFalse();
        assertThat(Files.exists(featureDir)).isFalse();
        assertThat(Files.exists(headsDir)).isTrue();
    }
}
