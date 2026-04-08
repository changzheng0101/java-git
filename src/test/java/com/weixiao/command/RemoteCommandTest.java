package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.JitTestUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteCommandTest {

    private static final CommandLine CLI = Jit.createCommandLine();

    @Test
    void list_verbose_and_remove_errors(@TempDir Path dir) throws Exception {
        assertThat(JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "init").getExitCode()).isZero();

        String config = """
                [remote "origin"]
                \turl = git@github.com:test/jit.git
                \tfetch = +refs/heads/*:refs/remotes/origin/*
                """;
        Files.writeString(dir.resolve(".git/config"), config, StandardCharsets.UTF_8);

        JitTestUtil.ExecuteResult list = JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "remote");
        assertThat(list.getExitCode()).isZero();
        assertThat(list.getOutput().trim()).isEqualTo("origin");

        JitTestUtil.ExecuteResult verbose = JitTestUtil.executeWithCapturedOut(
                CLI, "-C", dir.toString(), "remote", "-v");
        assertThat(verbose.getExitCode()).isZero();
        String vOut = verbose.getOutput();
        assertThat(vOut).contains("origin\tgit@github.com:test/jit.git (fetch)");
        assertThat(vOut).contains("origin\tgit@github.com:test/jit.git (push)");

        assertThat(JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "remote", "remove", "origin")
                .getExitCode()).isZero();

        JitTestUtil.ExecuteResult afterRm = JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "remote");
        assertThat(afterRm.getOutput().trim()).isEmpty();

        JitTestUtil.ExecuteResult missing = JitTestUtil.executeWithCapturedOut(
                CLI, "-C", dir.toString(), "remote", "remove", "nope");
        assertThat(missing.getExitCode()).isNotZero();
        assertThat(missing.getErr()).contains("No such remote");

        JitTestUtil.ExecuteResult bad = JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "remote", "nope");
        assertThat(bad.getExitCode()).isNotZero();
        assertThat(bad.getErr()).contains("unknown subcommand");
    }
    @Test
    void remove_rm_alias_and_preserve_others(@TempDir Path dir) throws Exception {
        // 初始化仓库
        assertThat(JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "init").getExitCode()).isZero();

        // 写入两个 remote：origin 与 upstream（值使用仅字母与点，便于当前解析器匹配）
        String config = """
                [remote "origin"]
                \turl = origin.example.git
                [remote "upstream"]
                \turl = upstream.example.git
                """;
        Files.writeString(dir.resolve(".git/config"), config, StandardCharsets.UTF_8);

        // 初始列表应包含两个 remote，且顺序与配置一致
        JitTestUtil.ExecuteResult list1 = JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "remote");
        assertThat(list1.getExitCode()).isZero();
        assertThat(list1.getOutput().trim().split("\\R")).containsExactly("origin", "upstream");

        // 使用别名 rm 移除 origin，退出码应为 0
        JitTestUtil.ExecuteResult rm = JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "remote", "rm", "origin");
        assertThat(rm.getExitCode()).isZero();

        // 再次列出只剩 upstream
        JitTestUtil.ExecuteResult list2 = JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "remote");
        assertThat(list2.getExitCode()).isZero();
        assertThat(list2.getOutput().trim()).isEqualTo("upstream");

        // -v 模式下应打印 fetch/push 两行，且 push 未设置时回退为 url
        JitTestUtil.ExecuteResult verbose = JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "remote", "-v");
        assertThat(verbose.getExitCode()).isZero();
        String vOut = verbose.getOutput();
        assertThat(vOut).contains("upstream\tupstream.example.git (fetch)");
        assertThat(vOut).contains("upstream\tupstream.example.git (push)");

        // 配置文件中不应再包含 origin 节，但应包含 upstream 节
        String cfgText = Files.readString(dir.resolve(".git/config"), StandardCharsets.UTF_8);
        assertThat(cfgText).doesNotContain("[remote \"origin\"]");
        assertThat(cfgText).contains("[remote \"upstream\"]");
    }
    @Test
    void verbose_with_distinct_pushurl(@TempDir Path dir) throws Exception {
        // 初始化仓库
        assertThat(JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "init").getExitCode()).isZero();

        // 写入 origin，设置 url 与 pushurl 不同
        String config = """
                [remote "origin"]
                \turl = origin.example.git
                \tpushurl = push.example.git
                """;
        Files.writeString(dir.resolve(".git/config"), config, StandardCharsets.UTF_8);

        // -v 模式应分别展示 fetch/push
        JitTestUtil.ExecuteResult verbose = JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "remote", "-v");
        assertThat(verbose.getExitCode()).isZero();
        String out = verbose.getOutput();
        assertThat(out).contains("origin\torigin.example.git (fetch)");
        assertThat(out).contains("origin\tpush.example.git (push)");
    }

    @Test
    void remove_multiple_remotes_step_by_step(@TempDir Path dir) throws Exception {
        // 初始化仓库
        assertThat(JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "init").getExitCode()).isZero();

        // 写入两个 remote：a、b，其中 a 还包含 pushurl
        String config = """
                [remote "a"]
                \turl = a.example.git
                \tpushurl = apush.example.git
                [remote "b"]
                \turl = b.example.git
                """;
        Files.writeString(dir.resolve(".git/config"), config, StandardCharsets.UTF_8);

        // 初始应列出 a 与 b
        JitTestUtil.ExecuteResult list1 = JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "remote");
        assertThat(list1.getExitCode()).isZero();
        assertThat(list1.getOutput().trim().split("\\R")).containsExactly("a", "b");

        // 先删除 a（使用 remove 子命令）
        JitTestUtil.ExecuteResult rmA = JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "remote", "remove", "a");
        assertThat(rmA.getExitCode()).isZero();

        // 检查列表与文件内容
        JitTestUtil.ExecuteResult list2 = JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "remote");
        assertThat(list2.getExitCode()).isZero();
        assertThat(list2.getOutput().trim()).isEqualTo("b");
        String cfgAfterA = Files.readString(dir.resolve(".git/config"), StandardCharsets.UTF_8);
        assertThat(cfgAfterA).doesNotContain("[remote \"a\"]");
        assertThat(cfgAfterA).contains("[remote \"b\"]");

        // 再删除 b
        JitTestUtil.ExecuteResult rmB = JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "remote", "remove", "b");
        assertThat(rmB.getExitCode()).isZero();

        // 列表应为空
        JitTestUtil.ExecuteResult list3 = JitTestUtil.executeWithCapturedOut(CLI, "-C", dir.toString(), "remote");
        assertThat(list3.getExitCode()).isZero();
        assertThat(list3.getOutput().trim()).isEmpty();
    }
}
