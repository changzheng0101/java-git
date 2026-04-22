package com.weixiao.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.util.List;

/**
 * jit remote：列出远程、{@code -v}/{@code --verbose} 显示 URL，{@code remove}/{@code rm} 删除配置节。
 */
@Command(name = "remote", mixinStandardHelpOptions = true, description = "管理远程仓库")
public class RemoteCommand extends BaseCommand {

    @SuppressWarnings("unused")
    @Option(names = {"-v", "--verbose"}, description = "显示 fetch 与 push URL")
    private boolean verbose;

    @SuppressWarnings("unused")
    @Parameters(arity = "0..*", paramLabel = "ARGS", description = "例如 remove <name>")
    private List<String> args;

    @Override
    protected void doRun() {
        List<String> a = args == null || args.isEmpty() ? List.of() : args;
        if (a.isEmpty()) {
            for (String line : repo.getRemotes().listingLines(verbose)) {
                System.out.println(line);
            }
            return;
        }

        String sub = a.get(0);
        if ("remove".equals(sub) || "rm".equals(sub)) {
            if (a.size() < 2) {
                System.err.println("error: remote name required");
                exitCode = 1;
                return;
            }
            if (a.size() > 2) {
                System.err.println("error: too many arguments");
                exitCode = 1;
                return;
            }
            try {
                repo.getRemotes().remove(a.get(1));
            } catch (IOException e) {
                System.err.println("error: " + e.getMessage());
                exitCode = 1;
            }
            return;
        }

        System.err.println("error: unknown subcommand: " + sub);
        exitCode = 1;
    }
}
