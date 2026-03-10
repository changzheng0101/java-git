package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.repo.Repository;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.nio.file.Path;
import java.util.Map;

/**
 * 命令基类：提供统一的命令行参数 Map（params）、initParams 与仓库查找逻辑。
 * run() 固定流程：exitCode=0 → initParams() → [findRepository()] → doRun()；子类实现 doRun() 即可。
 * 默认 {@link #requiresRepository()} 返回 true，run() 会自动查找仓库并赋值给 {@link #repo}；
 * 找不到时 exitCode=1 并直接返回，不调用 doRun()。
 * 不依赖仓库的命令（如 init）可覆盖 {@link #requiresRepository()} 返回 false。
 * 实现 picocli 的 {@link CommandLine.IExitCodeGenerator}，使 {@link CommandLine#execute(String[])} 返回本命令的 exitCode。
 */
@Command
public abstract class BaseCommand implements Runnable, CommandLine.IExitCodeGenerator {

    @picocli.CommandLine.ParentCommand
    protected Jit jit;

    /**
     * 命令行参数：由 initParams() 填充，通过 {@link #isSet(String)} / {@link #get(String)} 访问。
     */
    protected Map<String, String> params;

    protected int exitCode = 0;

    /**
     * 当前命令关联的仓库单例；requiresRepository()=true 时由 run() 初始化后可在 doRun() 中直接使用。
     */
    protected Repository repo;

    @Override
    public final void run() {
        exitCode = 0;
        initParams();
        if (requiresRepository()) {
            repo = Repository.find(jit.getStartPath());
            if (repo == null) {
                exitCode = 1;
                return;
            }
        }
        doRun();
    }

    /**
     * 是否需要仓库；默认 true。不依赖仓库的命令（如 init）可覆盖返回 false。
     */
    protected boolean requiresRepository() {
        return true;
    }

    /**
     * 子类实现具体命令逻辑；需要仓库时直接使用 {@link #repo} 字段即可。
     */
    protected abstract void doRun();

    /**
     * 子类可选覆盖：将 @Option / @Parameters 等转换为 params 键值；布尔标志为 true 时 put(key, "")。
     * 默认空实现，不需要时可不覆盖。
     */
    protected void initParams() {
    }

    /**
     * 返回 jit 工作目录（如 init 不依赖仓库时使用）。
     */
    protected final Path getStartPath() {
        return jit.getStartPath();
    }

    /**
     * 是否设置了该键（布尔标志为 true 时即视为设置，值为空字符串）。
     */
    protected boolean isSet(String key) {
        return params != null && params.containsKey(key);
    }

    /**
     * 获取字符串参数，未设置时返回 null。
     */
    protected String get(String key) {
        return params != null ? params.get(key) : null;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
