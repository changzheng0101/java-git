package com.weixiao.diff;

import com.weixiao.Jit;
import com.weixiao.JitTestUtil;
import com.weixiao.repo.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TreeDiff 测试")
class TreeDiffTest {

    private static final CommandLine JIT = Jit.createCommandLine();

    @Test
    @DisplayName("根目录 tree：B 新增 c.txt，删除 b.txt，a.txt 不变则 created/deleted 正确、modified 为空")
    void diff_createdAndDeleted(@TempDir Path dir) throws Exception {
        JIT.execute("-C", dir.toString(), "init");
        Repository repo = Repository.find(dir);

        Files.write(dir.resolve("a.txt"), "a".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("b.txt"), "b".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "add", "a.txt", "b.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "commit", "-m", "first");
        String commitOidA = repo.getRefs().readHead();

        Files.delete(dir.resolve("b.txt"));
        Files.write(dir.resolve("c.txt"), "c".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "add", "c.txt");
        repo.getIndex().load();
        repo.getIndex().remove("b.txt");
        repo.getIndex().save();
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "commit", "-m", "second");
        String commitOidB = repo.getRefs().readHead();

        List<DiffEntry> entries = TreeDiff.diff(commitOidA, commitOidB, Paths.get(""));

        List<DiffEntry> created = entries.stream()
            .filter(e -> e.getStatus() == DiffEntry.DiffStatus.CREATED)
            .collect(Collectors.toList());
        List<DiffEntry> deleted = entries.stream()
            .filter(e -> e.getStatus() == DiffEntry.DiffStatus.DELETED)
            .collect(Collectors.toList());

        assertThat(created).hasSize(1);
        assertThat(created.get(0).getName()).isEqualTo("c.txt");

        assertThat(deleted).hasSize(1);
        assertThat(deleted.get(0).getName()).isEqualTo("b.txt");

        assertThat(entries.stream().filter(e -> e.getStatus() == DiffEntry.DiffStatus.MODIFIED)).isEmpty();
    }

    @Test
    @DisplayName("同一路径 a.txt 在 A 与 B 中 oid 或 mode 不同则出现在 modified")
    void diff_modified(@TempDir Path dir) throws Exception {
        JIT.execute("-C", dir.toString(), "init");
        Repository repo = Repository.find(dir);

        Files.write(dir.resolve("a.txt"), "v1".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "add", "a.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "commit", "-m", "first");
        String commitOidA = repo.getRefs().readHead();

        Files.write(dir.resolve("a.txt"), "v2".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "add", "a.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "commit", "-m", "second");
        String commitOidB = repo.getRefs().readHead();

        List<DiffEntry> entries = TreeDiff.diff(commitOidA, commitOidB, Paths.get(""));

        assertThat(entries.stream().filter(e -> e.getStatus() == DiffEntry.DiffStatus.CREATED)).isEmpty();
        assertThat(entries.stream().filter(e -> e.getStatus() == DiffEntry.DiffStatus.DELETED)).isEmpty();
        List<DiffEntry> modified = entries.stream()
            .filter(e -> e.getStatus() == DiffEntry.DiffStatus.MODIFIED)
            .collect(Collectors.toList());
        assertThat(modified).hasSize(1);
        assertThat(modified.get(0).getName()).isEqualTo("a.txt");
        assertThat(modified.get(0).getEntryA().getOid()).isNotEqualTo(modified.get(0).getEntryB().getOid());
    }

    @Test
    @DisplayName("mode 不同、oid 相同也视为 modified")
    void diff_modifiedModeOnly(@TempDir Path dir) throws Exception {
        JIT.execute("-C", dir.toString(), "init");
        Repository repo = Repository.find(dir);

        Path f = dir.resolve("f");
        Files.write(f, "x".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "add", "f");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "commit", "-m", "first");
        String commitOidA = repo.getRefs().readHead();

        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "add", "f");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "commit", "-m", "second");
        String commitOidB = repo.getRefs().readHead();

        List<DiffEntry> entries = TreeDiff.diff(commitOidA, commitOidB, Paths.get(""));

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
        JIT.execute("-C", dir.toString(), "init");
        Repository repo = Repository.find(dir);

        Files.write(dir.resolve("f"), "same".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "add", "f");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "commit", "-m", "first");
        String commitOidA = repo.getRefs().readHead();

        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "add", "f");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "commit", "-m", "second");
        String commitOidB = repo.getRefs().readHead();

        List<DiffEntry> entries = TreeDiff.diff(commitOidA, commitOidB, Paths.get(""));

        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("prefix 指定子目录时比较该目录下的 tree")
    void diff_withPrefix(@TempDir Path dir) throws Exception {
        JIT.execute("-C", dir.toString(), "init");
        Repository repo = Repository.find(dir);

        Files.createDirectories(dir.resolve("dir"));
        Files.write(dir.resolve("dir").resolve("inner.txt"), "1".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "add", "dir");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "commit", "-m", "first");
        String commitOidA = repo.getRefs().readHead();

        Files.write(dir.resolve("dir").resolve("inner.txt"), "2".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "add", "dir");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "commit", "-m", "second");
        String commitOidB = repo.getRefs().readHead();

        List<DiffEntry> entries = TreeDiff.diff(commitOidA, commitOidB, Paths.get(""));

        List<DiffEntry> modified = entries.stream()
            .filter(e -> e.getStatus() == DiffEntry.DiffStatus.MODIFIED)
            .collect(Collectors.toList());
        assertThat(modified).hasSize(1);
        assertThat(modified.get(0).getName()).isEqualTo("inner.txt");
        assertThat(modified.get(0).getEntryA().getOid()).isNotEqualTo(modified.get(0).getEntryB().getOid());
    }

}
