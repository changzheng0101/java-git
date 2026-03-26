package com.weixiao.command;

import com.weixiao.obj.Commit;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.PendingCommit;
import com.weixiao.repo.WriteCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * jit commit - 将暂存区（index）中的文件提交到仓库。
 * 与 Git 一致：只提交已 add 到 index 的内容，不直接扫描工作区。
 * 当前仅支持 -m 指定提交信息（不打开编辑器）。
 */
@Command(name = "commit", mixinStandardHelpOptions = true, description = "提交变更到仓库")
public class CommitCommand extends BaseCommand {

    private static final Logger log = LoggerFactory.getLogger(CommitCommand.class);

    @SuppressWarnings("unused")
    @Option(names = {"-m", "--message"}, description = "提交信息")
    private String message;

    @Override
    protected void initParams() {
        params = new LinkedHashMap<>();
        if (message != null) {
            params.put("message", message);
        }
    }

    /**
     * 从 Jit 工作目录查找仓库，从 index 构建 tree 并提交，更新当前分支（HEAD 指向的 ref）；index 为空时失败。
     */
    @Override
    protected void doRun() {
        log.debug("commit start path={}", getStartPath());
        log.debug("repo root={}", repo.getRoot());

        try {
            repo.getIndex().load();
            // check for conflict status
            PendingCommit pendingCommit = new PendingCommit(repo.getGitDir());
            if (pendingCommit.inProgress()) {
                handleConflictCommit(pendingCommit);
                return;
            }

            if (repo.getIndex().isEmpty()) {
                log.info("commit aborted: nothing added to commit");
                System.err.println("fatal: no changes added to commit (use \"jit add\")");
                exitCode = 1;
                return;
            }
            String msg = get("message");
            if (msg == null || msg.isBlank()) {
                System.err.println("fatal: commit message is required (use -m)");
                exitCode = 1;
                return;
            }

            String parentOid = repo.getRefs().readHead();
            log.debug("parent oid={}", parentOid);
            List<String> parents = (parentOid == null || parentOid.isEmpty())
                    ? Collections.emptyList()
                    : Collections.singletonList(parentOid);
            String commitOid = WriteCommit.writeCommit(parents, msg);

            log.info("commit created oid={}", commitOid);
            System.out.println("[" + ObjectDatabase.shortOid(commitOid) + "] " + Commit.firstLine(msg));
        } catch (IOException e) {
            log.error("commit failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }

    private void handleConflictCommit(PendingCommit pendingCommit) throws IOException {
        if (repo.getIndex().isConflicted()) {
            System.err.println("error: cannot continue, unresolved conflicts remain.");
            exitCode = 1;
            return;
        }
        String headOid = repo.getRefs().readHead();
        String mergeHeadOid = pendingCommit.readMergeHead();
        String mergeMessage = pendingCommit.readMergeMessage();
        String commitOid = WriteCommit.writeCommit(Arrays.asList(headOid, mergeHeadOid), mergeMessage);
        pendingCommit.clear();
        System.out.println("Merge made. New commit: " + ObjectDatabase.shortOid(commitOid));
    }

}
