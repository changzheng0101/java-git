package com.weixiao.command;

import com.weixiao.obj.Commit;
import com.weixiao.obj.GitObject;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Repository;
import com.weixiao.utils.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * jit log - 显示提交历史。
 * 当前实现：从 HEAD 开始沿 parent 链向后遍历，每次读取并输出一个 commit（流式输出，不缓存完整历史）。
 */
@Command(name = "log", mixinStandardHelpOptions = true, description = "显示提交历史")
public class LogCommand extends BaseCommand {

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

    @Option(names = {"--abbrev-commit"}, description = "使用缩写的提交 ID（默认 7 位）")
    private boolean abbrevCommit;

    @Option(names = {"--no-abbrev-commit"}, description = "使用完整的 40 位提交 ID")
    private boolean noAbbrevCommit;

    @Option(names = {"--oneline"}, description = "每个提交一行：<abbrev-commit> <title line>")
    private boolean oneline;

    @Override
    protected void initParams() {
        params = new LinkedHashMap<>();
        if (abbrevCommit) {
            params.put("abbrevCommit", "");
        }
        if (noAbbrevCommit) {
            params.put("noAbbrevCommit", "");
        }
        if (oneline) {
            params.put("oneline", "");
        }
    }

    @Override
    protected void doRun() {
        log.debug("log start path={}", getStartPath());
        try {
            String headOid = repo.getRefs().readHead();
            if (headOid == null || headOid.isEmpty()) {
                System.err.println("fatal: Not a valid object name: 'HEAD'.");
                exitCode = 1;
                return;
            }

            String headBranchName = repo.getRefs().getCurrentBranchName();
            LogRefInfo refInfo = new LogRefInfo(headOid, headBranchName);

            walkCommits(headOid, refInfo);
        } catch (IOException e) {
            log.error("log failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }

    /**
     * 从给定 oid 开始沿 parent 链遍历提交，每次输出一个 commit。格式相关标志从 params 读取。
     */
    private void walkCommits(String startOid, LogRefInfo refInfo) throws IOException {
        boolean useAbbrev = isSet("oneline") || (isSet("abbrevCommit") && !isSet("noAbbrevCommit"));
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
        String id = abbrev ? ObjectDatabase.shortOid(oid) : oid;
        if (isSet("oneline")) {
            String title = Commit.firstLine(commit.getMessage());
            System.out.println(Color.yellow(id) + refsStr + " " + title);
        } else {
            System.out.println("commit " + Color.yellow(id) + refsStr);
            System.out.println("Author: " + Commit.formatAuthorNameEmail(commit.getAuthor()));
            System.out.println("Date:   " + Commit.formatAuthorDate(commit.getAuthor()));
            System.out.println();
            System.out.println(Commit.firstLine(commit.getMessage()));
        }
    }

}

