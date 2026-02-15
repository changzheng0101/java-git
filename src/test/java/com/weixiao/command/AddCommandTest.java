package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.JitTestUtil;
import com.weixiao.JitTestUtil.ExecuteResult;
import com.weixiao.repo.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jit add 命令测试：多文件添加、目录递归、非仓库失败等。
 */
@DisplayName("AddCommand 测试")
class AddCommandTest {

    private static final CommandLine JIT = Jit.createCommandLine();

    @Test
    @DisplayName("在非仓库目录执行 add 失败并提示 not a jit repository")
    void add_outsideRepo_fails(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("a.txt");
        Files.write(f, "a".getBytes(StandardCharsets.UTF_8));
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", dir.toString(), "add", "a.txt");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getErr()).contains("not a jit repository");
    }

    @Test
    @DisplayName("init 后 add 单个文件，暂存区有且仅有一条记录")
    void add_singleFile_succeeds(@TempDir Path tempDir) throws Exception {
        Jit.createCommandLine().execute("-C", tempDir.toString(), "init");
        Path f = tempDir.resolve("hello.txt");
        Files.write(f, "hello".getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "hello.txt");
        assertThat(result.getExitCode()).isEqualTo(0);

        Repository repo = Repository.find(tempDir);
        assertThat(repo).isNotNull();
        repo.getIndex().load();
        List<com.weixiao.repo.Index.Entry> entries = repo.getIndex().getEntries();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getPath()).isEqualTo("hello.txt");
        assertThat(entries.get(0).getOid()).hasSize(40);
    }

    @Test
    @DisplayName("add 多个文件，暂存区包含所有文件")
    void add_multipleFiles_succeeds(@TempDir Path tempDir) throws Exception {
        Jit.createCommandLine().execute("-C", tempDir.toString(), "init");
        Files.write(tempDir.resolve("a.txt"), "a".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("b.txt"), "b".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("c.txt"), "c".getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "a.txt", "b.txt", "c.txt");
        assertThat(result.getExitCode()).isEqualTo(0);

        Repository repo = Repository.find(tempDir);
        repo.getIndex().load();
        List<String> paths = repo.getIndex().getEntries().stream()
                .map(com.weixiao.repo.Index.Entry::getPath)
                .sorted()
                .collect(Collectors.toList());
        assertThat(paths).containsExactly("a.txt", "b.txt", "c.txt");
    }

    @Test
    @DisplayName("add 目录会递归添加其下所有文件")
    void add_directory_addsAllFiles(@TempDir Path tempDir) throws Exception {
        Jit.createCommandLine().execute("-C", tempDir.toString(), "init");
        Path sub = tempDir.resolve("dir");
        Files.createDirectories(sub);
        Files.write(sub.resolve("f1.txt"), "f1".getBytes(StandardCharsets.UTF_8));
        Files.write(sub.resolve("f2.txt"), "f2".getBytes(StandardCharsets.UTF_8));

        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "dir");
        assertThat(result.getExitCode()).isEqualTo(0);

        Repository repo = Repository.find(tempDir);
        repo.getIndex().load();
        List<String> paths = repo.getIndex().getEntries().stream()
                .map(com.weixiao.repo.Index.Entry::getPath)
                .sorted()
                .collect(Collectors.toList());
        assertThat(paths).containsExactly("dir/f1.txt", "dir/f2.txt");
    }

    /**
     * 避免 hello.txt 与 hello.txt/a.txt 同时存在：先 add hello.txt，再 add hello.txt/a.txt 后，
     * index 中只保留 hello.txt/a.txt。
     */
    @Test
    @DisplayName("add hello.txt 再 add hello.txt/a.txt 时只保留 hello.txt/a.txt")
    void add_helloThenHelloSlashA_removesHello(@TempDir Path tempDir) throws Exception {
        Jit.createCommandLine().execute("-C", tempDir.toString(), "init");
        Files.write(tempDir.resolve("hello.txt"), "hello".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "hello.txt");

        Path helloDir = tempDir.resolve("hello.txt");
        Files.delete(helloDir);
        Files.createDirectories(helloDir);
        Files.write(helloDir.resolve("a.txt"), "a".getBytes(StandardCharsets.UTF_8));
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "hello.txt/a.txt");
        assertThat(result.getExitCode()).as("add hello.txt/a.txt err: %s", result.getErr()).isEqualTo(0);

        Repository repo = Repository.find(tempDir);
        repo.getIndex().load();
        List<String> paths = repo.getIndex().getEntries().stream()
                .map(com.weixiao.repo.Index.Entry::getPath)
                .sorted()
                .collect(Collectors.toList());
        assertThat(paths).containsExactly("hello.txt/a.txt");
        assertThat(paths).doesNotContain("hello.txt");
    }
}
