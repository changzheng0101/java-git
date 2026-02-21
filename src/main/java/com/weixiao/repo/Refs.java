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
 * 引用：读取/写入 HEAD、refs/heads/*，分支名校验（与 Git check-ref-format 一致）。
 */
public final class Refs {

    private static final Logger log = LoggerFactory.getLogger(Refs.class);

    private static final String HEAD = "HEAD";
    /** refs/heads/ 前缀，分支完整 ref 为 refs/heads/&lt;name&gt; */
    public static final String REFS_HEADS = "refs/heads/";
    private static final String REFS_HEADS_MASTER = REFS_HEADS + "master";
    private static final Pattern HEAD_REF = Pattern.compile("ref:\\s*(.+)");

    private static final String DOUBLE_DOT = "..";
    private static final String DOT_LOCK = ".lock";

    private final Path gitDir;

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
        return readRef(ref);
    }

    /**
     * 读取指定 ref 指向的 oid，不存在或无法读取时返回 null。
     */
    public String readRef(String ref) throws IOException {
        if (ref == null || ref.isEmpty()) return null;
        Path refPath = gitDir.resolve(ref);
        if (!Files.exists(refPath)) return null;
        return Files.readString(refPath, StandardCharsets.UTF_8).trim();
    }

    /**
     * 写入 ref 指向给定 oid，必要时创建父目录。
     */
    public void writeRef(String ref, String oid) throws IOException {
        Path refPath = gitDir.resolve(ref);
        Path dir = refPath.getParent();
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        Files.writeString(refPath, oid + "\n", StandardCharsets.UTF_8);
        log.debug("writeRef ref={} oid={}", ref, oid);
    }

    /**
     * 将 refs/heads/master 更新为给定 commit oid。
     */
    public void updateMaster(String oid) throws IOException {
        writeRef(REFS_HEADS_MASTER, oid);
    }

    /**
     * 校验分支名是否合法（对应 refs/heads/&lt;name&gt; 的 name 部分）。
     * 规则与 Git check-ref-format 一致：无 ..、无非法字符、不以/开头或结尾、无//、不以.结尾、各段不以.开头且不以.lock结尾。
     *
     * @return 若合法返回 null，否则返回错误原因描述
     */
    public static String validateBranchName(String name) {
        if (name == null || name.isEmpty()) {
            return "branch name is empty";
        }
        if (name.contains(DOUBLE_DOT)) {
            return "branch name must not contain '..'";
        }
        if (name.startsWith("/") || name.endsWith("/")) {
            return "branch name must not start or end with '/'";
        }
        if (name.contains("//")) {
            return "branch name must not contain '//'";
        }
        if (name.endsWith(".")) {
            return "branch name must not end with '.'";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || c == 127 || c == ' ' || c == '~' || c == '^' || c == ':' || c == '?' || c == '*' || c == '[' || c == '\\') {
                return "branch name contains illegal character: '" + c + "'";
            }
            if (c == '@') {
                if (i + 1 < name.length() && name.charAt(i + 1) == '{') {
                    return "branch name must not contain '@{'";
                }
                if (name.length() == 1) {
                    return "branch name must not be '@'";
                }
            }
        }
        for (String segment : name.split("/")) {
            if (segment.startsWith(".")) {
                return "branch name segment must not start with '.'";
            }
            if (segment.endsWith(DOT_LOCK)) {
                return "branch name segment must not end with '.lock'";
            }
        }
        return null;
    }

    /**
     * 判断分支名是否合法。
     */
    public static boolean isValidBranchName(String name) {
        return validateBranchName(name) == null;
    }

    /**
     * 判断 refs/heads/&lt;name&gt; 是否已存在。
     */
    public boolean branchExists(String name) throws IOException {
        Path refPath = gitDir.resolve(REFS_HEADS + name);
        return Files.exists(refPath);
    }

    /**
     * 创建分支：将 refs/heads/&lt;name&gt; 指向 oid。调用前需已校验 name 合法且分支不存在。
     */
    public void createBranch(String name, String oid) throws IOException {
        writeRef(REFS_HEADS + name, oid);
        log.debug("createBranch name={} oid={}", name, oid);
    }
}
