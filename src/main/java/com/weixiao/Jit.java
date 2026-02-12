package com.weixiao;

import com.weixiao.command.AddCommand;
import com.weixiao.command.CommitCommand;
import com.weixiao.command.InitCommand;
import com.weixiao.command.StatusCommand;
import picocli.CommandLine;
import picocli.CommandLine.*;

/**
 * jit - 用 Java 实现的 Git 风格命令行入口。
 * 替代 git，通过子命令扩展功能（如 init、commit、status 等）。
 * <p>
 * 所有 jit 命令的执行都应通过此类作为唯一入口点。
 */
@Command(name = "jit", mixinStandardHelpOptions = true, description = "jit - 版本控制")
public class Jit implements Runnable {

    /**
     * 未指定子命令时打印用法说明。
     */
    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    /**
     * 创建配置好的 CommandLine 实例，包含所有已注册的子命令。
     * 这是执行 jit 命令的统一入口点，供 main() 和测试使用。
     *
     * @return 配置好的 CommandLine 实例
     */
    public static CommandLine createCommandLine() {
        return new CommandLine(new Jit())
                .addSubcommand("init", new InitCommand())
                .addSubcommand("add", new AddCommand())
                .addSubcommand("commit", new CommitCommand())
                .addSubcommand("status", new StatusCommand());
    }

    /**
     * 主入口方法，执行 jit 命令。
     * 若需调试日志：-Djit.debug=true 或环境变量 JIT_DEBUG=true，或 -Djit.log.level=DEBUG。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        if ("true".equalsIgnoreCase(System.getProperty("jit.debug"))
                || "true".equalsIgnoreCase(System.getenv("JIT_DEBUG"))) {
            System.setProperty("jit.log.level", "DEBUG");
        }
        CommandLine cli = createCommandLine();
        String[] runArgs = args != null && args.length > 0 ? args : new String[]{"--help"};
        int exitCode = cli.execute(runArgs);
        System.exit(exitCode);
    }
}
