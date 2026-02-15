package com.weixiao.command;

import com.weixiao.Jit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * jit init - 在 Jit 指定的工作目录下创建空的 .git 仓库结构。
 * 参考 jcoglan/jit：创建 .git、.git/objects、.git/refs/heads。
 */
@Command(name = "init", mixinStandardHelpOptions = true, description = "创建空的 jit 仓库")
public class InitCommand implements Runnable, IExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(InitCommand.class);

    private static final String GIT_DIR = ".git";
    private static final String DIR_OBJECTS = "objects";
    private static final String DIR_REFS_HEADS = "refs/heads";
    private static final String DEFAULT_BRANCH = "master";
    private static final String HEAD_REF = "ref: refs/heads/" + DEFAULT_BRANCH;

    @ParentCommand
    private Jit jit;

    private int exitCode = 0;

    /** 在 Jit 工作目录下创建 .git、.git/objects、.git/refs/heads 并写入 HEAD 指向 refs/heads/master，成功时输出一行提示。 */
    @Override
    public void run() {
        exitCode = 0;
        Path root = jit.getStartPath();
        Path gitPath = root.resolve(GIT_DIR);
        log.debug("init root={} gitPath={}", root, gitPath);

        List<String> dirs = new ArrayList<>();
        dirs.add(DIR_OBJECTS);
        dirs.add(DIR_REFS_HEADS);

        try {
            Files.createDirectories(gitPath);
            for (String dir : dirs) {
                Path sub = gitPath.resolve(dir);
                Files.createDirectories(sub);
                log.debug("created dir {}", sub);
            }
            Path headFile = gitPath.resolve("HEAD");
            Files.write(headFile, HEAD_REF.getBytes(StandardCharsets.UTF_8));
            log.debug("wrote HEAD -> {}", HEAD_REF);
        } catch (IOException e) {
            log.error("init failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
            return;
        }

        log.info("repository initialized at {}", gitPath);
        System.out.println("Initialized empty Jit repository in " + gitPath);
    }

    /** 返回本命令的退出码（0 成功，1 失败）。 */
    @Override
    public int getExitCode() {
        return exitCode;
    }
}
