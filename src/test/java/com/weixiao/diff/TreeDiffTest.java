package com.weixiao.diff;

import com.weixiao.obj.Blob;
import com.weixiao.obj.Commit;
import com.weixiao.obj.Tree;
import com.weixiao.obj.TreeEntry;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TreeDiff 测试")
class TreeDiffTest {

    @Test
    @DisplayName("根目录 tree：B 新增 c.txt，删除 b.txt，a.txt 不变则 created/deleted 正确、modified 为空")
    void diff_createdAndDeleted(@TempDir Path dir) throws Exception {
        prepareGitDir(dir);
        ObjectDatabase db = new ObjectDatabase(dir.resolve(".git"));
        Repository repo = new Repository(dir);

        Blob blobA = new Blob("a".getBytes(StandardCharsets.UTF_8));
        Blob blobB = new Blob("b".getBytes(StandardCharsets.UTF_8));
        Blob blobC = new Blob("c".getBytes(StandardCharsets.UTF_8));
        String oidA = db.store(blobA);
        String oidB = db.store(blobB);
        String oidC = db.store(blobC);

        Tree treeCommitA = new Tree(Arrays.asList(
            new TreeEntry("100644", "a.txt", oidA),
            new TreeEntry("100644", "b.txt", oidB)
        ));
        String treeOidA = db.store(treeCommitA);
        Commit commitA = Commit.first(treeOidA, "u <u@local> 0 +0000", "first");
        String commitOidA = db.store(commitA);

        Tree treeCommitB = new Tree(Arrays.asList(
            new TreeEntry("100644", "a.txt", oidA),
            new TreeEntry("100644", "c.txt", oidC)
        ));
        String treeOidB = db.store(treeCommitB);
        Commit commitB = new Commit(treeOidB, commitOidA, "u", "u", "second");
        String commitOidB = db.store(commitB);

        List<DiffEntry> entries = TreeDiff.diff(repo, commitOidA, commitOidB, "");

        List<DiffEntry> created = entries.stream()
            .filter(e -> e.getStatus() == DiffEntry.DiffStatus.CREATED)
            .collect(Collectors.toList());
        List<DiffEntry> deleted = entries.stream()
            .filter(e -> e.getStatus() == DiffEntry.DiffStatus.DELETED)
            .collect(Collectors.toList());

        assertThat(created).hasSize(1);
        assertThat(created.get(0).getName()).isEqualTo("c.txt");
        assertThat(created.get(0).getEntryB().getOid()).isEqualTo(oidC);

        assertThat(deleted).hasSize(1);
        assertThat(deleted.get(0).getName()).isEqualTo("b.txt");
        assertThat(deleted.get(0).getEntryA().getOid()).isEqualTo(oidB);

        assertThat(entries.stream().filter(e -> e.getStatus() == DiffEntry.DiffStatus.MODIFIED)).isEmpty();
    }

    @Test
    @DisplayName("同一路径 a.txt 在 A 与 B 中 oid 或 mode 不同则出现在 modified")
    void diff_modified(@TempDir Path dir) throws Exception {
        prepareGitDir(dir);
        ObjectDatabase db = new ObjectDatabase(dir.resolve(".git"));
        Repository repo = new Repository(dir);

        Blob blob1 = new Blob("v1".getBytes(StandardCharsets.UTF_8));
        Blob blob2 = new Blob("v2".getBytes(StandardCharsets.UTF_8));
        String oid1 = db.store(blob1);
        String oid2 = db.store(blob2);

        Tree treeA = new Tree(Collections.singletonList(new TreeEntry("100644", "a.txt", oid1)));
        Tree treeB = new Tree(Collections.singletonList(new TreeEntry("100644", "a.txt", oid2)));
        String treeOidA = db.store(treeA);
        String treeOidB = db.store(treeB);
        Commit commitA = Commit.first(treeOidA, "u <u@local> 0 +0000", "first");
        Commit commitB = new Commit(treeOidB, db.store(commitA), "u", "u", "second");
        String commitOidA = db.store(commitA);
        String commitOidB = db.store(commitB);

        List<DiffEntry> entries = TreeDiff.diff(repo, commitOidA, commitOidB, "");

        assertThat(entries.stream().filter(e -> e.getStatus() == DiffEntry.DiffStatus.CREATED)).isEmpty();
        assertThat(entries.stream().filter(e -> e.getStatus() == DiffEntry.DiffStatus.DELETED)).isEmpty();
        List<DiffEntry> modified = entries.stream()
            .filter(e -> e.getStatus() == DiffEntry.DiffStatus.MODIFIED)
            .collect(Collectors.toList());
        assertThat(modified).hasSize(1);
        assertThat(modified.get(0).getName()).isEqualTo("a.txt");
        assertThat(modified.get(0).getEntryA().getOid()).isEqualTo(oid1);
        assertThat(modified.get(0).getEntryB().getOid()).isEqualTo(oid2);
    }

    @Test
    @DisplayName("mode 不同、oid 相同也视为 modified")
    void diff_modifiedModeOnly(@TempDir Path dir) throws Exception {
        prepareGitDir(dir);
        ObjectDatabase db = new ObjectDatabase(dir.resolve(".git"));
        Repository repo = new Repository(dir);

        Blob blob = new Blob("x".getBytes(StandardCharsets.UTF_8));
        String oid = db.store(blob);

        Tree treeA = new Tree(Collections.singletonList(new TreeEntry("100644", "f", oid)));
        Tree treeB = new Tree(Collections.singletonList(new TreeEntry("100755", "f", oid)));
        String treeOidA = db.store(treeA);
        String treeOidB = db.store(treeB);
        Commit commitA = Commit.first(treeOidA, "u <u@local> 0 +0000", "first");
        Commit commitB = new Commit(treeOidB, db.store(commitA), "u", "u", "second");
        String commitOidA = db.store(commitA);
        String commitOidB = db.store(commitB);

        List<DiffEntry> entries = TreeDiff.diff(repo, commitOidA, commitOidB, "");

        List<DiffEntry> modified = entries.stream()
            .filter(e -> e.getStatus() == DiffEntry.DiffStatus.MODIFIED)
            .collect(Collectors.toList());
        assertThat(modified).hasSize(1);
        assertThat(modified.get(0).getEntryA().getMode()).isEqualTo("100644");
        assertThat(modified.get(0).getEntryB().getMode()).isEqualTo("100755");
    }

    @Test
    @DisplayName("无变化时 created、deleted、modified 均为空")
    void diff_noChange(@TempDir Path dir) throws Exception {
        prepareGitDir(dir);
        ObjectDatabase db = new ObjectDatabase(dir.resolve(".git"));
        Repository repo = new Repository(dir);

        Blob blob = new Blob("same".getBytes(StandardCharsets.UTF_8));
        String oid = db.store(blob);
        Tree tree = new Tree(Collections.singletonList(new TreeEntry("100644", "f", oid)));
        String treeOid = db.store(tree);
        Commit commitA = Commit.first(treeOid, "u <u@local> 0 +0000", "first");
        String commitOidA = db.store(commitA);
        Commit commitB = new Commit(treeOid, commitOidA, "u", "u", "second");
        String commitOidB = db.store(commitB);

        List<DiffEntry> entries = TreeDiff.diff(repo, commitOidA, commitOidB, "");

        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("prefix 指定子目录时比较该目录下的 tree")
    void diff_withPrefix(@TempDir Path dir) throws Exception {
        prepareGitDir(dir);
        ObjectDatabase db = new ObjectDatabase(dir.resolve(".git"));
        Repository repo = new Repository(dir);

        Blob blob1 = new Blob("1".getBytes(StandardCharsets.UTF_8));
        Blob blob2 = new Blob("2".getBytes(StandardCharsets.UTF_8));
        String oid1 = db.store(blob1);
        String oid2 = db.store(blob2);

        Tree subTreeA = new Tree(Collections.singletonList(new TreeEntry("100644", "inner.txt", oid1)));
        Tree subTreeB = new Tree(Collections.singletonList(new TreeEntry("100644", "inner.txt", oid2)));
        String subOidA = db.store(subTreeA);
        String subOidB = db.store(subTreeB);

        Tree rootA = new Tree(Collections.singletonList(new TreeEntry("40000", "dir", subOidA)));
        Tree rootB = new Tree(Collections.singletonList(new TreeEntry("40000", "dir", subOidB)));
        String rootOidA = db.store(rootA);
        String rootOidB = db.store(rootB);
        Commit commitA = Commit.first(rootOidA, "u <u@local> 0 +0000", "first");
        Commit commitB = new Commit(rootOidB, db.store(commitA), "u", "u", "second");
        String commitOidA = db.store(commitA);
        String commitOidB = db.store(commitB);

        List<DiffEntry> entries = TreeDiff.diff(repo, commitOidA, commitOidB, "dir");

        List<DiffEntry> modified = entries.stream()
            .filter(e -> e.getStatus() == DiffEntry.DiffStatus.MODIFIED)
            .collect(Collectors.toList());
        assertThat(modified).hasSize(1);
        assertThat(modified.get(0).getName()).isEqualTo("inner.txt");
        assertThat(modified.get(0).getEntryA().getOid()).isEqualTo(oid1);
        assertThat(modified.get(0).getEntryB().getOid()).isEqualTo(oid2);
    }

    private static void prepareGitDir(Path dir) throws Exception {
        Path gitDir = dir.resolve(".git");
        Files.createDirectories(gitDir.resolve("objects"));
        Files.createDirectories(gitDir.resolve("refs").resolve("heads"));
    }
}
