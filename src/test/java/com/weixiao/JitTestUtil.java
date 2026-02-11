package com.weixiao;

import lombok.Value;
import lombok.experimental.UtilityClass;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

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

    /** 执行结果：退出码 + 标准输出 + 标准错误 */
    @Value
    public static class ExecuteResult {
        int exitCode;
        String output;
        String err;
    }
}
