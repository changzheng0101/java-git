package com.weixiao;

import com.weixiao.repo.Repository;
import lombok.Value;
import lombok.experimental.UtilityClass;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jit 测试用工具方法，供各命令测试类复用。
 */
@UtilityClass
public class JitTestUtil {

    /**
     * 重定向 stdout/stderr、执行给定 CommandLine、恢复，返回退出码和捕获的输出。
     *
     * @param cli  配置好的 jit CommandLine（如 Jit.createCommandLine()）
     * @param args 命令参数（如 "init", path 等）
     * @return 退出码、标准输出、标准错误
     */
    public static ExecuteResult executeWithCapturedOut(CommandLine cli, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        PrintStream captureOut = new PrintStream(out, true);
        PrintStream captureErr = new PrintStream(err, true);
        System.setOut(captureOut);
        System.setErr(captureErr);
        try {
            int exitCode = cli.execute(args);
            captureOut.flush();
            captureErr.flush();
            return new ExecuteResult(exitCode, out.toString(), err.toString());
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    /**
     * 初始化仓库并创建一次提交，返回该提交的 HEAD oid。
     */
    public static String initRepoWithOneCommit(CommandLine cli, Path repoDir, String message) throws Exception {
        return initRepoWithOneCommit(cli, repoDir, "f.txt", "v1", message);
    }

    /**
     * 初始化仓库并创建一次提交，返回该提交的 HEAD oid。
     */
    public static String initRepoWithOneCommit(
            CommandLine cli,
            Path repoDir,
            String fileName,
            String fileContent,
            String message
    ) throws Exception {
        cli.execute("-C", repoDir.toString(), "init");
        Path file = repoDir.resolve(fileName);
        Files.writeString(file, fileContent);
        executeWithCapturedOut(cli, "-C", repoDir.toString(), "add", fileName);
        ExecuteResult commit = executeWithCapturedOut(cli, "-C", repoDir.toString(), "commit", "-m", message);
        assertThat(commit.getExitCode())
                .as("commit out: %s err: %s", commit.getOutput(), commit.getErr())
                .isEqualTo(0);
        Repository repo = Repository.find(repoDir);
        assertThat(repo).isNotNull();
        return repo.getRefs().readHead();
    }

    /**
     * 初始化仓库并创建两次线性提交，返回第二次提交后的 HEAD oid。
     */
    public static String initRepoWithTwoCommits(CommandLine cli, Path repoDir) throws Exception {
        return initRepoWithTwoCommits(cli, repoDir, "f.txt", "v1", "first", "v2", "second");
    }

    /**
     * 初始化仓库并创建两次线性提交，返回第二次提交后的 HEAD oid。
     */
    public static String initRepoWithTwoCommits(
            CommandLine cli,
            Path repoDir,
            String fileName,
            String firstContent,
            String firstMessage,
            String secondContent,
            String secondMessage
    ) throws Exception {
        initRepoWithOneCommit(cli, repoDir, fileName, firstContent, firstMessage);
        Path file = repoDir.resolve(fileName);
        Files.writeString(file, secondContent);
        executeWithCapturedOut(cli, "-C", repoDir.toString(), "add", fileName);
        ExecuteResult commit = executeWithCapturedOut(cli, "-C", repoDir.toString(), "commit", "-m", secondMessage);
        assertThat(commit.getExitCode())
                .as("commit out: %s err: %s", commit.getOutput(), commit.getErr())
                .isEqualTo(0);
        Repository repo = Repository.find(repoDir);
        assertThat(repo).isNotNull();
        return repo.getRefs().readHead();
    }

    /** 执行结果：退出码 + 标准输出 + 标准错误 */
    @Value
    public static class ExecuteResult {
        int exitCode;
        String output;
        String err;
    }
}
