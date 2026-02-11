package com.weixiao.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * jit init - 在当前目录或指定路径创建空的 .git 仓库结构。
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

    @Parameters(index = "0", arity = "0..1", description = "仓库根路径，默认为当前目录")
    private Path path;

    private int exitCode = 0;

    /** 在指定或当前目录创建 .git、.git/objects、.git/refs/heads 并写入 HEAD 指向 refs/heads/master，成功时输出一行提示。 */
    @Override
    public void run() {
        Path root = path != null ? path.toAbsolutePath().normalize() : Paths.get("").toAbsolutePath().normalize();
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
