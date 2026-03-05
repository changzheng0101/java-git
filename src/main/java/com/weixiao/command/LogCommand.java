package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.obj.Commit;
import com.weixiao.obj.GitObject;
import com.weixiao.repo.Refs;
import com.weixiao.repo.Repository;
import com.weixiao.repo.SysRef;
import com.weixiao.utils.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * jit log - 显示提交历史。
 * 当前实现：从 HEAD 开始沿 parent 链向后遍历，每次读取并输出一个 commit（流式输出，不缓存完整历史）。
 */
@Command(name = "log", mixinStandardHelpOptions = true, description = "显示提交历史")
public class LogCommand implements Runnable, IExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(LogCommand.class);

    /**
     * log 遍历时用于显示 (HEAD -&gt; branch) 等 ref 信息的上下文；branchNamesToOid 在使用时由 walkCommits 再获取。
     */
    private static final class LogRefInfo {
        final String headOid;
        final String headBranchName;

        LogRefInfo(String headOid, String headBranchName) {
            this.headOid = headOid;
            this.headBranchName = headBranchName;
        }
    }

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

            SysRef headRef = repo.getRefs().getHeadRef();
            String headBranchName = (headRef != null && headRef.getPath().startsWith(Refs.REFS_HEADS))
                    ? headRef.getPath().substring(Refs.REFS_HEADS.length()) : null;
            LogRefInfo refInfo = new LogRefInfo(headOid, headBranchName);

            walkCommits(headOid, refInfo);
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
     * 从给定 oid 开始沿 parent 链遍历提交，每次输出一个 commit。格式相关标志（abbrev、oneline）直接使用本命令成员变量。
     */
    private void walkCommits(String startOid, LogRefInfo refInfo) throws IOException {
        boolean useAbbrev = oneline || (abbrevCommit && !noAbbrevCommit);
        String oid = startOid;
        while (oid != null) {
            GitObject obj = Repository.INSTANCE.getDatabase().load(oid);
            if (!"commit".equals(obj.getType())) {
                log.warn("skip non-commit object in history: {} type={}", oid, obj.getType());
                break;
            }
            Commit commit = Commit.fromBytes(obj.toBytes());
            String refsStr = formatRefsAtCommit(oid, refInfo);
            printCommit(oid, commit, useAbbrev, refsStr);
            oid = commit.getParentOid();
        }
    }

    /**
     * 格式化为 Git 风格的 (HEAD -&gt; master, other) 或 (branch) 等。
     */
    private static String formatRefsAtCommit(String commitOid, LogRefInfo refInfo) throws IOException {
        Map<String, String> branchNamesToOid = Repository.INSTANCE.getRefs().getBranchNamesToOid();
        List<String> parts = new ArrayList<>();
        boolean isHead = commitOid.equals(refInfo.headOid);
        if (isHead && refInfo.headBranchName != null) {
            parts.add("HEAD -> " + refInfo.headBranchName);
        } else if (isHead) {
            parts.add("HEAD");
        }
        for (Map.Entry<String, String> e : branchNamesToOid.entrySet()) {
            if (!e.getValue().equals(commitOid)) {
                continue;
            }
            if (e.getKey().equals(refInfo.headBranchName)) {
                continue;
            }
            parts.add(e.getKey());
        }
        if (parts.isEmpty()) {
            return "";
        }
        return " (" + String.join(", ", parts) + ")";
    }

    private void printCommit(String oid, Commit commit, boolean abbrev, String refsStr) {
        String id = abbrev && oid != null && oid.length() > 7 ? oid.substring(0, 7) : oid;
        if (oneline) {
            String title = firstLine(commit.getMessage());
            System.out.println(Color.yellow(id) + refsStr + " " + title);
        } else {
            System.out.println("commit " + Color.yellow(id) + refsStr);
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

