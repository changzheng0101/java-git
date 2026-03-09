package com.weixiao.command;

import com.google.common.base.Strings;
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
import java.util.ArrayList;
import java.util.Collections;
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

    @Option(names = {"--abbrev-commit"}, description = "使用缩写的提交 ID（默认 7 位）")
    private boolean abbrevCommit;

    @Option(names = {"--no-abbrev-commit"}, description = "使用完整的 40 位提交 ID")
    private boolean noAbbrevCommit;

    @Option(names = {"--oneline"}, description = "每个提交一行：<abbrev-commit> <title line>")
    private boolean oneline;

    /**
     * 接受如下参数：
     * 默认 HEAD
     * 多个Revision
     * A..B（仅显示在 B 可达且 A 不可达的提交）
     */
    @Parameters(index = "0", arity = "0..1", paramLabel = "REVISION", description = "默认 HEAD；可为单个修订（分支/oid/HEAD）或 A..B")
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
    }

    @Override
    protected void doRun() {
        log.debug("log start path={} revisions={}", getStartPath(), get("revisions"));
        try {
            RevList.RevSpecResult spec = RevList.parseRevSpecs(revisions);

            boolean useAbbrev = isSet("oneline") || (isSet("abbrevCommit") && !isSet("noAbbrevCommit"));
            RevList.walk(spec, entry -> {
                String refsStr = formatRefsAtCommit(entry.oid());
                printCommit(entry.oid(), entry.commit(), useAbbrev, refsStr);
            });
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

    /**
     * 格式化为 Git 风格的 (HEAD -&gt; master, other) 或 (branch) 等。
     */
    private static String formatRefsAtCommit(String commitOid) {
        Map<String, String> branchNamesToOid = Repository.INSTANCE.getRefs().getBranchNamesToOid();
        String headOid = Repository.INSTANCE.getRefs().readHead();
        String headBranchName = Repository.INSTANCE.getRefs().getCurrentBranchName();
        List<String> parts = new ArrayList<>();
        boolean isHead = commitOid.equals(headOid);
        if (isHead && headBranchName != null) {
            parts.add("HEAD -> " + headBranchName);
        } else if (isHead) {
            parts.add("HEAD");
        }
        for (Map.Entry<String, String> e : branchNamesToOid.entrySet()) {
            if (!e.getValue().equals(commitOid)) {
                continue;
            }
            if (e.getKey().equals(headBranchName)) {
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
            if (!Strings.isNullOrEmpty(msg)) {
                System.out.println(msg.replaceAll("(?m)^", "    "));
            }
            System.out.println();
        }
    }

}

