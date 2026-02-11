package com.weixiao;

import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * jit 测试用工具方法，供各命令测试类复用。
 */
public final class JitTestUtil {

    private JitTestUtil() {}

    /**
     * 重定向 stdout、执行给定 CommandLine、恢复 stdout，返回退出码和捕获的输出。
     *
     * @param cli  配置好的 jit CommandLine（如 Jit.createCommandLine()）
     * @param args 命令参数（如 "init", path 等）
     * @return 退出码与标准输出内容
     */
    public static ExecuteResult executeWithCapturedOut(CommandLine cli, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream capture = new PrintStream(out, true);
        System.setOut(capture);
        try {
            int exitCode = cli.execute(args);
            capture.flush();
            return new ExecuteResult(exitCode, out.toString());
        } finally {
            System.setOut(origOut);
        }
    }

    /** 执行结果：退出码 + 捕获的标准输出 */
    public static final class ExecuteResult {
        public final int exitCode;
        public final String output;

        public ExecuteResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
