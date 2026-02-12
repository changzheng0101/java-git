package com.weixiao.command;

import com.weixiao.obj.Commit;
import com.weixiao.obj.Tree;
import com.weixiao.obj.TreeEntry;
import com.weixiao.repo.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * jit commit - 将暂存区（index）中的文件提交到仓库。
 * 与 Git 一致：只提交已 add 到 index 的内容，不直接扫描工作区。
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

    /** 从 start 路径查找仓库，从 index 构建 tree 并提交，更新 refs/heads/master；index 为空时失败。 */
    @Override
    public void run() {
        exitCode = 0;
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
            repo.getIndex().load();
            if (repo.getIndex().isEmpty()) {
                log.info("commit aborted: nothing added to commit");
                System.err.println("fatal: no changes added to commit (use \"jit add\")");
                exitCode = 1;
                return;
            }
            String treeOid = buildTreeFromIndex(repo, repo.getIndex().getEntries(), "");
            log.debug("stored root tree oid={}", treeOid);

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

    /**
     * 从 index 的扁平 path 列表递归构建 Tree。
     * prefix 为当前目录相对仓库根的路径（如 "" 或 "dir/"），只处理 path.startsWith(prefix) 的条目。
     * 返回当前目录的 tree oid。
     */
    private String buildTreeFromIndex(Repository repo, List<com.weixiao.repo.Index.Entry> indexEntries, String prefix) throws IOException {
        List<TreeEntry> entries = new ArrayList<>();
        String prefixSlash = prefix.isEmpty() ? "" : prefix;

        // add for normal file
        for (com.weixiao.repo.Index.Entry e : indexEntries) {
            if (!e.getPath().startsWith(prefixSlash)) continue;
            String local = prefixSlash.isEmpty() ? e.getPath() : e.getPath().substring(prefixSlash.length());
            if (local.isEmpty()) continue;
            if (!local.contains("/")) {
                entries.add(new TreeEntry(e.getMode(), local, e.getOid()));
                log.debug("tree entry blob {} mode={} oid={}", local, e.getMode(), e.getOid());
            }
        }

        // add for directory
        Set<String> dirNames = new HashSet<>();
        for (com.weixiao.repo.Index.Entry e : indexEntries) {
            if (!e.getPath().startsWith(prefixSlash)) continue;
            String local = prefixSlash.isEmpty() ? e.getPath() : e.getPath().substring(prefixSlash.length());
            if (local.contains("/")) {
                dirNames.add(local.substring(0, local.indexOf('/')));
            }
        }
        for (String dirName : dirNames.stream().sorted().collect(Collectors.toList())) {
            String subPrefix = prefixSlash + dirName + "/";
            String childTreeOid = buildTreeFromIndex(repo, indexEntries, subPrefix);
            entries.add(new TreeEntry("40000", dirName, childTreeOid));
            log.debug("tree entry dir {} -> {}", dirName, childTreeOid);
        }

        Tree tree = new Tree(entries);
        String treeOid = repo.getDatabase().store(tree);
        log.debug("stored tree prefix={} oid={}", prefix, treeOid);
        return treeOid;
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
