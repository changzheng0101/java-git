package com.weixiao.command;

import com.weixiao.obj.Blob;
import com.weixiao.obj.Commit;
import com.weixiao.obj.Tree;
import com.weixiao.obj.TreeEntry;
import com.weixiao.repo.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * jit commit - 将工作区快照提交到仓库。
 * 当前仅支持 -m 指定提交信息（不打开编辑器）。
 */
@Command(name = "commit", mixinStandardHelpOptions = true, description = "提交变更到仓库")
public class CommitCommand implements Runnable, IExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CommitCommand.class);

    @Option(names = {"-m", "--message"}, required = true, description = "提交信息")
    private String message;

    @Parameters(index = "0", arity = "0..1", description = "仓库根路径，默认为当前目录")
    private Path path;

    private int exitCode = 0;

    /** 从 start 路径查找仓库，将工作区文件打成 blob/tree/commit 写入对象库并更新 refs/heads/master，失败时写 stderr 并设 exitCode=1。 */
    @Override
    public void run() {
        Path start = path != null ? path.toAbsolutePath().normalize() : Paths.get("").toAbsolutePath().normalize();
        log.debug("commit start path={}", start);
        Repository repo = Repository.find(start);
        if (repo == null) {
            log.debug("no repo found from {}", start);
            log.info("commit aborted: not a jit repository");
            System.err.println("fatal: not a jit repository (or any of the parent directories): .git");
            exitCode = 1;
            return;
        }
        log.debug("repo root={}", repo.getRoot());

        try {
            List<String> files = repo.getWorkspace().listFiles();
            log.debug("listFiles count={} files={}", files.size(), files);
            List<TreeEntry> entries = new ArrayList<>();
            for (String name : files) {
                byte[] data = repo.getWorkspace().readFile(name);
                Blob blob = new Blob(data);
                String blobOid = repo.getDatabase().store(blob);
                log.debug("stored blob {} -> {}", name, blobOid);
                entries.add(TreeEntry.regularFile(name, blobOid));
            }

            Tree tree = new Tree(entries);
            String treeOid = repo.getDatabase().store(tree);
            log.debug("stored tree oid={}", treeOid);

            String parentOid = repo.getRefs().readHead();
            log.debug("parent oid={}", parentOid);
            String author = formatAuthor();
            Commit commit = parentOid == null
                    ? Commit.first(treeOid, author, message)
                    : new Commit(treeOid, parentOid, author, author, message);
            String commitOid = repo.getDatabase().store(commit);
            log.debug("stored commit oid={}", commitOid);

            repo.getRefs().updateMaster(commitOid);
            log.info("commit created oid={} tree={}", commitOid, treeOid);
            System.out.println("[" + commitOid.substring(0, 7) + "] " + (message != null && message.contains("\n") ? message.substring(0, message.indexOf('\n')) : message));
        } catch (IOException e) {
            log.error("commit failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }

    /** 生成当前作者字符串，格式：Name &lt;name@local&gt; timestamp +0000。 */
    private static String formatAuthor() {
        String user = System.getProperty("user.name", "user");
        long sec = System.currentTimeMillis() / 1000;
        return user + " <" + user + "@local> " + sec + " +0000";
    }

    /** 返回本命令的退出码（0 成功，1 失败）。 */
    @Override
    public int getExitCode() {
        return exitCode;
    }
}
