package com.weixiao.repo;

import com.weixiao.config.JitConfig;
import com.weixiao.model.StatusResult;
import com.weixiao.obj.Blob;
import com.weixiao.obj.Commit;
import com.weixiao.obj.GitObject;
import com.weixiao.obj.Tree;
import com.weixiao.obj.TreeEntry;
import com.weixiao.utils.CryptoUtils;
import com.weixiao.utils.HexUtils;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 仓库：定位 .git 目录，提供 ObjectDatabase、Refs、Workspace。
 * 全局单例，{@link #find(Path)} 会更新该实例的属性（root、database 等），不创建新实例。
 */
@Getter
public final class Repository {

    private static final Logger log = LoggerFactory.getLogger(Repository.class);

    private static final String GIT_DIR = ".git";

    public static final Repository INSTANCE = new Repository();

    /**
     * 工作区根目录（即仓库根）
     */
    private Path root;
    /**
     * .git 目录路径
     */
    private Path gitDir;
    /**
     * 对象库，用于 store/load blob、tree、commit
     */
    private final ObjectDatabase database;
    /**
     * 引用，用于 readHead、updateMaster
     */
    private final Refs refs;
    /**
     * 工作区，用于 listFiles、readFile
     */
    private final Workspace workspace;
    /**
     * 暂存区，用于 add/commit
     */
    private final Index index;
    /**
     * 本仓库唯一持有的 {@code .git/config} 访问对象；未 {@link #find(Path)} 前为 null。
     */
    private JitConfig jitConfig;

    /**
     * 单例构造，初始时各属性为 null；通过 {@link #find(Path)} 查找成功后会被 {@link #init(Path)} 填充。
     */
    private Repository() {
        this.root = null;
        this.gitDir = null;
        this.database = new ObjectDatabase();
        this.refs = new Refs();
        this.workspace = new Workspace();
        this.index = new Index();
        this.jitConfig = null;
    }

    /**
     * 用指定仓库根更新单例属性（root、gitDir、database、refs、workspace、index）。
     */
    private void init(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.gitDir = this.root.resolve(GIT_DIR);
        this.database.setObjectsDir(this.gitDir.resolve("objects"));
        this.refs.setGitDir(this.gitDir);
        this.workspace.setRoot(this.root);
        this.index.setGitDir(this.gitDir);
        this.jitConfig = new JitConfig(this.gitDir.resolve("config"));
    }

    /**
     * 未找到仓库时打印到 stderr 的提示。
     */
    public static final String FATAL_NOT_A_REPO = "fatal: not a jit repository (or any of the parent directories): .git";

    /**
     * 从 start 向上查找包含 .git 的目录作为仓库根；找到时更新全局单例并返回，未找到时打印 {@link #FATAL_NOT_A_REPO} 到 stderr 并返回 null。
     */
    public static Repository find(Path start) {
        Path resolved = resolveRoot(start);
        if (resolved == null) {
            System.err.println(FATAL_NOT_A_REPO);
            return null;
        }
        INSTANCE.init(resolved);
        return INSTANCE;
    }

    /**
     * 从 start 向上查找 .git 所在目录作为仓库根；未找到返回 null。
     */
    private static Path resolveRoot(Path start) {
        Path current = start.toAbsolutePath().normalize();
        Path root = Paths.get("/").normalize();
        log.debug("find repo start={}", current);
        while (current != null && !current.equals(root)) {
            if (Files.exists(current.resolve(GIT_DIR))
                    && Files.isDirectory(current.resolve(GIT_DIR))) {
                log.info("found repo at {}", current);
                return current;
            }
            current = current.getParent();
        }
        log.debug("no repo found");
        return null;
    }

    /**
     * 将指定 commit 的 tree 展开为 path -> TreeEntry，填入传入的 map（仅文件路径，不含目录）。
     * 用于 status、diff、checkout 等需要目标 tree 快照的场景。
     */
    public void collectCommitTreeTo(String commitOid, Map<String, TreeEntry> pathToEntry) throws IOException {
        GitObject obj = database.load(commitOid);
        if (!"commit".equals(obj.getType())) {
            throw new IOException("not a commit: " + commitOid);
        }
        String treeOid = parseCommitTreeOid(obj.toBytes());
        if (treeOid == null) {
            throw new IOException("invalid commit format: " + commitOid);
        }
        pathToEntry.clear();
        collectTreePathToEntry(treeOid, "", pathToEntry);
    }

    private static String parseCommitTreeOid(byte[] commitBody) {
        String content = new String(commitBody, java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.startsWith("tree ")) {
                return line.substring(5).trim();
            }
        }
        return null;
    }

    private void collectTreePathToEntry(String treeOid, String prefix, Map<String, TreeEntry> pathToEntry) throws IOException {
        GitObject treeRaw = database.load(treeOid);
        if (!"tree".equals(treeRaw.getType())) {
            throw new IOException("expected tree, got " + treeRaw.getType() + ": " + treeOid);
        }
        List<TreeEntry> entries;
        try {
            entries = Tree.fromBytes(treeRaw.toBytes()).getEntries();
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid tree: " + treeOid, e);
        }
        for (TreeEntry entry : entries) {
            String path = prefix.isEmpty() ? entry.getName() : prefix + "/" + entry.getName();
            if ("40000".equals(entry.getMode())) {
                collectTreePathToEntry(entry.getOid(), path, pathToEntry);
            } else {
                pathToEntry.put(path, entry);
            }
        }
    }

    /**
     * 计算 status：workspace vs index、index vs HEAD 的差异。委托给 {@link Status#getStatus()}。
     * 调用前需已调用 {@link Index#load()}。
     */
    public StatusResult getStatus() throws IOException {
        return Status.getStatus();
    }

    /**
     * 加载 commit 并返回其 message 首行；非 commit 或加载失败时返回空串。
     */
    public String getCommitShortMessage(String commitOid) {
        Commit commit = database.loadCommit(commitOid);
        if (commit == null) {
            return "";
        }
        return Commit.firstLine(commit.getMessage());
    }

    public static String computeBlobOid(byte[] data) {
        Blob blob = new Blob(data);
        byte[] body = blob.toBytes();
        String header = blob.getType() + " " + body.length + "\0";
        byte[] headerBytes = header.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] content = new byte[headerBytes.length + body.length];
        System.arraycopy(headerBytes, 0, content, 0, headerBytes.length);
        System.arraycopy(body, 0, content, headerBytes.length, body.length);
        byte[] digest = CryptoUtils.sha1(content);
        return HexUtils.bytesToHex(digest);
    }
}
