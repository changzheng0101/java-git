package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.obj.Commit;
import com.weixiao.obj.GitObject;
import com.weixiao.repo.Repository;
import com.weixiao.utils.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Path;

/**
 * jit log - 显示提交历史。
 * 当前实现：从 HEAD 开始沿 parent 链向后遍历，每次读取并输出一个 commit（流式输出，不缓存完整历史）。
 */
@Command(name = "log", mixinStandardHelpOptions = true, description = "显示提交历史")
public class LogCommand implements Runnable, IExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(LogCommand.class);

    @ParentCommand
    private Jit jit;

    @Option(names = {"--abbrev-commit"}, description = "使用缩写的提交 ID（默认 7 位）")
    private boolean abbrevCommit;

    @Option(names = {"--no-abbrev-commit"}, description = "使用完整的 40 位提交 ID")
    private boolean noAbbrevCommit;

    @Option(names = {"--oneline"}, description = "每个提交一行：<abbrev-commit> <title line>")
    private boolean oneline;

    private int exitCode = 0;

    @Override
    public void run() {
        exitCode = 0;
        Path start = jit.getStartPath();
        log.debug("log start path={}", start);

        Repository repo = Repository.find(start);
        if (repo == null) {
            log.debug("no repo found from {}", start);
            System.err.println("fatal: not a jit repository (or any of the parent directories): .git");
            exitCode = 1;
            return;
        }

        try {
            String headOid = repo.getRefs().readHead();
            if (headOid == null || headOid.isEmpty()) {
                System.err.println("fatal: Not a valid object name: 'HEAD'.");
                exitCode = 1;
                return;
            }

            boolean useAbbrev = oneline || (abbrevCommit && !noAbbrevCommit);
            walkCommits(repo, headOid, useAbbrev, oneline);
        } catch (IOException e) {
            log.error("log failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    /**
     * 从给定 oid 开始沿 parent 链遍历提交，每次输出一个 commit。
     */
    private void walkCommits(Repository repo, String startOid, boolean abbrev, boolean oneLine) throws IOException {
        String oid = startOid;
        while (oid != null) {
            GitObject obj = repo.getDatabase().load(oid);
            if (!"commit".equals(obj.getType())) {
                log.warn("skip non-commit object in history: {} type={}", oid, obj.getType());
                break;
            }
            Commit commit = Commit.fromBytes(obj.toBytes());
            printCommit(oid, commit, abbrev, oneLine);
            oid = commit.getParentOid();
        }
    }

    private void printCommit(String oid, Commit commit, boolean abbrev, boolean oneLine) {
        String id = abbrev && oid != null && oid.length() > 7 ? oid.substring(0, 7) : oid;
        if (oneLine) {
            String title = firstLine(commit.getMessage());
            System.out.println(Color.yellow(id) + " " + title);
        } else {
            System.out.println("commit " + Color.yellow(id));
            System.out.println();
            System.out.println(commit.getMessage());
            System.out.println();
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i >= 0 ? s.substring(0, i).trim() : s.trim();
    }
}

