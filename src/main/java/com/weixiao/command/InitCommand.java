package com.weixiao.command;

import com.weixiao.repo.Refs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * jit init - 在 Jit 指定的工作目录下创建空的 .git 仓库结构。
 * 参考 jcoglan/jit：创建 .git、.git/objects、.git/refs/heads。
 */
@Command(name = "init", mixinStandardHelpOptions = true, description = "创建空的 jit 仓库")
public class InitCommand extends BaseCommand {

    private static final Logger log = LoggerFactory.getLogger(InitCommand.class);

    private static final String GIT_DIR = ".git";
    private static final String DIR_OBJECTS = "objects";
    private static final String DEFAULT_BRANCH = "master";

    private static String dirRefsHeads() {
        return Refs.REFS_HEADS.substring(0, Refs.REFS_HEADS.length() - 1);
    }

    private static String headRefContent() {
        return "ref: " + Refs.REFS_HEADS + DEFAULT_BRANCH;
    }

    @Override
    protected boolean requiresRepository() {
        return false;
    }

    @Override
    protected void initParams() {
        params = new LinkedHashMap<>();
    }

    /** 在 Jit 工作目录下创建 .git、.git/objects、.git/refs/heads 并写入 HEAD 指向 refs/heads/master，成功时输出一行提示。 */
    @Override
    protected void doRun() {
        Path root = getStartPath();
        Path gitPath = root.resolve(GIT_DIR);
        log.debug("init root={} gitPath={}", root, gitPath);

        List<String> dirs = new ArrayList<>();
        dirs.add(DIR_OBJECTS);
        dirs.add(dirRefsHeads());

        try {
            Files.createDirectories(gitPath);
            for (String dir : dirs) {
                Path sub = gitPath.resolve(dir);
                Files.createDirectories(sub);
                log.debug("created dir {}", sub);
            }
            Path headFile = gitPath.resolve("HEAD");
            String headRef = headRefContent();
            Files.writeString(headFile, headRef);
            log.debug("wrote HEAD -> {}", headRef);
        } catch (IOException e) {
            log.error("init failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
            return;
        }

        log.info("repository initialized at {}", gitPath);
        System.out.println("Initialized emptyTree Jit repository in " + gitPath);
    }
}
