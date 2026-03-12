package com.weixiao.command;

import com.weixiao.obj.Commit;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.TreeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
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

    @Option(names = {"-m", "--message"}, required = true, description = "提交信息")
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
            if (repo.getIndex().isEmpty()) {
                log.info("commit aborted: nothing added to commit");
                System.err.println("fatal: no changes added to commit (use \"jit add\")");
                exitCode = 1;
                return;
            }
            String treeOid = TreeBuilder.buildTreeFromIndex(repo.getIndex().getEntries());
            log.debug("stored root tree oid={}", treeOid);

            String parentOid = repo.getRefs().readHead();
            log.debug("parent oid={}", parentOid);
            String author = formatAuthor();
            String msg = get("message");
            List<String> parents = (parentOid == null || parentOid.isEmpty())
                    ? Collections.emptyList()
                    : Collections.singletonList(parentOid);
            Commit commit = new Commit(treeOid, parents, author, author, msg);
            String commitOid = repo.getDatabase().store(commit);
            log.debug("stored commit oid={}", commitOid);

            repo.getRefs().updateCurrentBranch(commitOid);
            log.info("commit created oid={} tree={}", commitOid, treeOid);
            System.out.println("[" + ObjectDatabase.shortOid(commitOid) + "] " + Commit.firstLine(msg));
        } catch (IOException e) {
            log.error("commit failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }

    /**
     * 生成当前作者字符串，格式：Name &lt;name@local&gt; timestamp +0000。
     */
    private static String formatAuthor() {
        String user = System.getProperty("user.name", "user");
        long sec = System.currentTimeMillis() / 1000;
        return user + " <" + user + "@local> " + sec + " +0000";
    }

}
