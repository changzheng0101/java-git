package com.weixiao.repo;

import com.weixiao.utils.Constants;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 引用：读取/写入 HEAD、refs/heads/*，分支名校验（与 Git check-ref-format 一致）。
 * 设计原则：
 * string 类型的 ref 应该为 refs/heads/master之类的全路径
 */
@Data
@NoArgsConstructor
public final class Refs {

    private static final Logger log = LoggerFactory.getLogger(Refs.class);

    private static final SysRef HEAD_REF = new SysRef("HEAD");
    /**
     * refs/heads/ 前缀，分支完整 ref 为 refs/heads/&lt;name&gt;
     */
    public static final String REFS_HEADS = "refs/heads/";
    private static final Pattern HEAD_REF_PATTERN = Pattern.compile("ref:\\s*(.+)");

    private static final String DOUBLE_DOT = "..";
    private static final String DOT_LOCK = ".lock";

    private Path gitDir;

    public Refs(Path gitDir) {
        this.gitDir = gitDir;
    }

    /**
     * 解析 HEAD，返回当前指向的 commit oid（symref 时读分支 ref，detached 时读 HEAD 内容）；若未设置则返回 null。
     *
     * @return HEAD对应的commitId
     */
    public String readHead() throws IOException {
        String content = getHeadContent();
        if (content == null || content.isEmpty()) {
            return null;
        }
        Matcher m = HEAD_REF_PATTERN.matcher(content);
        if (m.matches()) {
            return readRef(new SysRef(m.group(1).trim()));
        }
        return content;
    }

    /**
     * 返回 HEAD 指向的 ref（如 refs/heads/master）；若 HEAD 为 detached（直接存 commit id）或不存在则返回 null。
     */
    public SysRef getHeadRef() throws IOException {
        String content = getHeadContent();
        if (content == null) {
            return null;
        }
        Matcher m = HEAD_REF_PATTERN.matcher(content);
        if (!m.matches()) {
            return null;
        }
        return new SysRef(m.group(1).trim());
    }

    /**
     * 读取 HEAD 文件原始内容（trim 后）；不存在则返回 null。
     */
    private String getHeadContent() throws IOException {
        Path headFile = gitDir.resolve(HEAD_REF.getPath());
        if (!Files.exists(headFile)) {
            log.debug("getHeadContent: no HEAD file");
            return null;
        }
        return Files.readString(headFile, StandardCharsets.UTF_8).trim();
    }

    /**
     * 将 HEAD 直接指向 commit oid（detached HEAD）。用于在 detached 状态下提交时只移动 HEAD、不更新任何分支。
     */
    public void writeHeadOid(String oid) throws IOException {
        Path headFile = gitDir.resolve(HEAD_REF.getPath());
        Files.writeString(headFile, oid + "\n", StandardCharsets.UTF_8);
        log.debug("writeHeadOid detached HEAD -> {}", oid);
    }

    /**
     * 读取指定 ref 指向的 oid，不存在或无法读取时返回 null。
     * ref为branch name
     *
     * @return ref对应的commitId
     */
    public String readRef(SysRef ref) throws IOException {
        if (ref == null) {
            return null;
        }
        String path = ref.getPath();
        if (path == null || path.isEmpty()) {
            return null;
        }
        Path refPath = gitDir.resolve(path);
        if (!Files.exists(refPath)) {
            return null;
        }
        return Files.readString(refPath, StandardCharsets.UTF_8).trim();
    }

    /**
     * 写入 ref 指向给定 oid，必要时创建父目录。
     */
    public void writeRef(SysRef ref, String oid) throws IOException {
        if (ref == null) {
            throw new IllegalArgumentException("ref must not be null");
        }
        String path = ref.getPath();
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("ref.path must not be empty");
        }
        Path refPath = gitDir.resolve(path);
        Path dir = refPath.getParent();
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        Files.writeString(refPath, oid + "\n", StandardCharsets.UTF_8);
        log.debug("writeRef ref={} oid={}", path, oid);
    }

    /**
     * 提交后更新 HEAD：若 HEAD 为 symref（指向 refs/heads/xxx）则更新该分支 ref；若为 detached HEAD 则只将 HEAD 文件改为新 commit oid，不更新任何分支。
     */
    public void updateCurrentBranch(String oid) throws IOException {
        SysRef headRef = getHeadRef();
        if (headRef != null && headRef.getPath().startsWith(REFS_HEADS)) {
            writeRef(headRef, oid);
        } else {
            writeHeadOid(oid);
        }
    }

    /**
     * checkout 后更新 HEAD：若 target 为分支名则令 HEAD 指向该分支（symref）；否则令 HEAD 直接指向 commit（detached）。
     *
     * @param target   分支名或非分支（此时视为 checkout 到 commit，detached HEAD）
     * @param commitId 目标 commit oid
     */
    public void updateHead(String target, String commitId) throws IOException {
        if (branchExists(target)) {
            writeHeadToBranch(target);
        } else {
            writeHeadOid(commitId);
        }
    }

    /**
     * 将 HEAD 设为指向分支的 symref（ref: refs/heads/&lt;branchName&gt;）。
     */
    public void writeHeadToBranch(String branchName) throws IOException {
        Path headFile = gitDir.resolve(HEAD_REF.getPath());
        Files.writeString(headFile, "ref: " + REFS_HEADS + branchName + "\n", StandardCharsets.UTF_8);
        log.debug("writeHeadToBranch HEAD -> ref: {}", REFS_HEADS + branchName);
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
    public boolean branchExists(String name) {
        Path refPath = gitDir.resolve(REFS_HEADS + name);
        return Files.exists(refPath);
    }

    /**
     * 返回所有分支名到 commit oid 的映射（分支名为 refs/heads 下的相对路径，如 master、feature/bar）。
     * 用于 log 等命令显示 (HEAD -&gt; master) 等。
     */
    public Map<String, String> getBranchNamesToOid() throws IOException {
        Path headsDir = gitDir.resolve("refs").resolve("heads");
        Map<String, String> result = new LinkedHashMap<>();
        if (!Files.exists(headsDir)) {
            return result;
        }
        try (Stream<Path> stream = Files.walk(headsDir)) {
            stream.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> {
                        String name = headsDir.relativize(p).toString().replace(Constants.FILE_SEPARATOR, "/");
                        SysRef ref = new SysRef(REFS_HEADS + name);
                        try {
                            String oid = readRef(ref);
                            if (oid != null && !oid.isEmpty()) {
                                result.put(name, oid);
                            }
                        } catch (IOException e) {
                            log.debug("skip ref {}: {}", name, e.getMessage());
                        }
                    });
        }
        return result;
    }

    /**
     * 创建分支：将 refs/heads/&lt;name&gt; 指向 oid。调用前需已校验 name 合法且分支不存在。
     */
    public void createBranch(String name, String oid) throws IOException {
        writeRef(new SysRef(REFS_HEADS + Constants.FILE_SEPARATOR + name), oid);
        log.debug("createBranch name={} oid={}", name, oid);
    }

    /**
     * 删除分支：删除 refs/heads/&lt;name&gt; 指向的 ref，并在可能时清理空目录（在 .git/refs/heads 处停止）。
     *
     * @param name 分支名（不含 refs/heads/ 前缀）
     * @return 被删除分支原来指向的 commit oid
     * @throws IOException 当分支不存在或删除失败时抛出
     */
    public String deleteBranch(String name) throws IOException {
        if (name == null || name.isEmpty()) {
            throw new IOException("branch name is empty");
        }
        Path refPath = gitDir.resolve(REFS_HEADS + Constants.FILE_SEPARATOR + name);
        if (!Files.exists(refPath)) {
            throw new IOException("branch '" + name + "' not found.");
        }
        String oid = Files.readString(refPath, StandardCharsets.UTF_8).trim();
        Files.delete(refPath);
        log.debug("deleteBranch name={} oid={}", name, oid);

        // 清理可能为空的父目录，在 .git/refs/heads 处停止
        Path headsDir = gitDir.resolve("refs").resolve("heads");
        Path parent = refPath.getParent();
        while (parent != null && !parent.equals(headsDir)) {
            try (Stream<Path> children = Files.list(parent)) {
                if (children.findAny().isPresent()) {
                    break;
                }
            }
            Files.delete(parent);
            parent = parent.getParent();
        }
        return oid;
    }
}
