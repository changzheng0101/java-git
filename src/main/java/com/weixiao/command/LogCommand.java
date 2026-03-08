package com.weixiao.command;

import com.weixiao.obj.Commit;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Refs;
import com.weixiao.repo.RevList;
import com.weixiao.repo.Repository;
import com.weixiao.revision.RevisionParseException;
import com.weixiao.utils.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * jit log - 显示提交历史。
 * 基于 RevList（类似 git rev-list）获取提交列表，本命令仅负责格式化与输出。
 */
@Command(name = "log", mixinStandardHelpOptions = true, description = "显示提交历史")
public class LogCommand extends BaseCommand {

    private static final Logger log = LoggerFactory.getLogger(LogCommand.class);

    /**
     * log 输出时用于显示 (HEAD -&gt; branch) 等 ref 信息的上下文。
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

    @Parameters(index = "0", arity = "0..*", paramLabel = "REVISION", description = "要显示的修订（默认 HEAD），可多个")
    private List<String> revisions;

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
        if (revisions != null && !revisions.isEmpty()) {
            params.put("revisions", String.join("\n", revisions));
        }
    }

    @Override
    protected void doRun() {
        log.debug("log start path={} revisions={}", getStartPath(), get("revisions"));
        try {
            List<String> revList = parseRevisionsParam();
            if (revList.isEmpty()) {
                String headOid = Repository.INSTANCE.getRefs().readHead();
                if (headOid == null || headOid.isEmpty()) {
                    System.err.println("fatal: Not a valid object name: 'HEAD'.");
                    exitCode = 1;
                    return;
                }
            }

            Refs refs = Repository.INSTANCE.getRefs();
            String headOid = refs.readHead();
            String headBranchName = refs.getCurrentBranchName();
            LogRefInfo refInfo = new LogRefInfo(headOid != null ? headOid : "", headBranchName);

            String[] revArray = revList.isEmpty() ? new String[0] : revList.toArray(new String[0]);
            boolean useAbbrev = isSet("oneline") || (isSet("abbrevCommit") && !isSet("noAbbrevCommit"));
            for (RevList.CommitEntry entry : RevList.walk(revArray)) {
                String refsStr = formatRefsAtCommit(entry.oid(), refInfo);
                printCommit(entry.oid(), entry.commit(), useAbbrev, refsStr);
            }
        } catch (RevisionParseException e) {
            log.warn("log parse revision failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        } catch (IOException e) {
            log.error("log failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }

    private List<String> parseRevisionsParam() {
        String s = get("revisions");
        if (s == null || s.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        for (String rev : s.split("\n")) {
            String t = rev.trim();
            if (!t.isEmpty()) {
                list.add(t);
            }
        }
        return list;
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
            String msg = commit.getMessage();
            if (msg != null && !msg.isEmpty()) {
                System.out.println(msg.replaceAll("(?m)^", "    "));
            }
            System.out.println();
        }
    }

}

