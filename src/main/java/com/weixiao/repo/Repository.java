package com.weixiao.repo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 仓库：定位 .git 目录，提供 ObjectDatabase、Refs、Workspace。
 * 代表整个git仓库的一个抽象
 */
public final class Repository {

    private static final Logger log = LoggerFactory.getLogger(Repository.class);

    private static final String GIT_DIR = ".git";

    private final Path root;   // 工作区根
    private final Path gitDir; // .git 目录
    private final ObjectDatabase database;
    private final Refs refs;
    private final Workspace workspace;

    /**
     * 以给定路径为仓库根（工作区根），.git 为 root/.git，并创建 ObjectDatabase、Refs、Workspace。
     */
    public Repository(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.gitDir = this.root.resolve(GIT_DIR);
        this.database = new ObjectDatabase(gitDir);
        this.refs = new Refs(gitDir);
        this.workspace = new Workspace(this.root);
    }

    /**
     * 从当前目录向上查找包含 .git 的目录作为仓库根；未找到返回 null。
     */
    public static Repository find(Path start) {
        Path current = start.toAbsolutePath().normalize();
        Path root = Paths.get("/").normalize();
        log.debug("find repo start={}", current);
        while (current != null && !current.equals(root)) {
            if (java.nio.file.Files.exists(current.resolve(GIT_DIR))
                    && java.nio.file.Files.isDirectory(current.resolve(GIT_DIR))) {
                log.info("found repo at {}", current);
                return new Repository(current);
            }
            current = current.getParent();
        }
        log.debug("no repo found");
        return null;
    }

    /**
     * 工作区根目录（即仓库根）。
     */
    public Path getRoot() {
        return root;
    }

    /**
     * .git 目录路径。
     */
    public Path getGitDir() {
        return gitDir;
    }

    /**
     * 对象库，用于 store/load blob、tree、commit。
     */
    public ObjectDatabase getDatabase() {
        return database;
    }

    /**
     * 引用，用于 readHead、updateMaster。
     */
    public Refs getRefs() {
        return refs;
    }

    /**
     * 工作区，用于 listFiles、readFile。
     */
    public Workspace getWorkspace() {
        return workspace;
    }
}
