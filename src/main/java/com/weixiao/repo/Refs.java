package com.weixiao.repo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用：读取 HEAD、更新 refs/heads/master。
 */
public final class Refs {

    private static final Logger log = LoggerFactory.getLogger(Refs.class);

    private static final String HEAD = "HEAD";
    private static final String REFS_HEADS_MASTER = "refs/heads/master";
    private static final Pattern HEAD_REF = Pattern.compile("ref:\\s*(.+)");

    private final Path gitDir;

    /**
     * 以 .git 目录为基准，HEAD 与 refs 路径均相对于 gitDir。
     */
    public Refs(Path gitDir) {
        this.gitDir = gitDir;
    }

    /**
     * 解析 HEAD，返回当前分支上的 commit oid；若未设置则返回 null。
     */
    public String readHead() throws IOException {
        Path headFile = gitDir.resolve(HEAD);
        if (!Files.exists(headFile)) {
            log.debug("readHead: no HEAD file");
            return null;
        }
        String content = Files.readString(headFile, StandardCharsets.UTF_8).trim();
        Matcher m = HEAD_REF.matcher(content);
        if (!m.matches()) return null;
        String ref = m.group(1).trim();
        Path refPath = gitDir.resolve(ref);
        if (!Files.exists(refPath)) return null;
        String oid = Files.readString(refPath, StandardCharsets.UTF_8).trim();
        log.debug("readHead ref={} oid={}", ref, oid);
        return oid;
    }

    /**
     * 将 refs/heads/master 更新为给定 commit oid。
     */
    public void updateMaster(String oid) throws IOException {
        Path refPath = gitDir.resolve(REFS_HEADS_MASTER);
        Path dir = refPath.getParent();
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        Files.writeString(refPath, oid + "\n", StandardCharsets.UTF_8);
        log.debug("updateMaster oid={}", oid);
    }
}
