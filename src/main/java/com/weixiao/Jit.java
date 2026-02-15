package com.weixiao;

import com.weixiao.command.AddCommand;
import com.weixiao.command.CommitCommand;
import com.weixiao.command.InitCommand;
import com.weixiao.command.StatusCommand;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * jit - 用 Java 实现的 Git 风格命令行入口。
 * 替代 git，通过子命令扩展功能（如 init、commit、status 等）。
 * <p>
 * 所有 jit 命令的执行都应通过此类作为唯一入口点。
 * 仓库根目录（或命令起始目录）由本类的 -C / -d 统一提供，子命令通过 {@link #getStartPath()} 获取。
 */
@Command(name = "jit", mixinStandardHelpOptions = true, description = "jit - 版本控制")
public class Jit implements Runnable {

    @Option(names = {"-C", "-d", "--directory"}, paramLabel = "PATH",
            description = "以指定路径作为工作目录执行命令（默认为当前目录），子命令据此查找仓库根")
    private Path workingDirectory;

    /**
     * 未指定子命令时打印用法说明。
     */
    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    /**
     * 返回命令的起始路径（工作目录）。
     * 子命令应使用此路径：init 在此路径下创建 .git；add/commit/status 从此路径向上查找 .git 得到仓库根。
     *
     * @return 已规范化的绝对路径，不会为 null
     */
    public Path getStartPath() {
        Path base = workingDirectory != null ? workingDirectory : Paths.get("");
        return base.toAbsolutePath().normalize();
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
