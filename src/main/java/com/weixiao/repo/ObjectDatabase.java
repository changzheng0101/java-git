package com.weixiao.repo;

import com.google.common.base.Strings;
import com.weixiao.obj.Blob;
import com.weixiao.obj.Commit;
import com.weixiao.obj.GitObject;
import com.weixiao.obj.Tree;
import com.weixiao.utils.HexUtils;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * .git/objects 存储：按 oid 写入/读取对象，格式与 Git 一致（type size\0body，zlib 压缩）。
 * 可以直接访问文件系统
 */
@Data
@NoArgsConstructor
public final class ObjectDatabase {

    private static final Logger log = LoggerFactory.getLogger(ObjectDatabase.class);

    private Path objectsDir;

    /**
     * commit 加载缓存，避免重复加载。
     */
    private final Map<String, Commit> commitCache = new HashMap<>();

    public ObjectDatabase(Path objectsDir) {
        this.objectsDir = objectsDir;
    }

    /**
     * 存储对象，返回 40 字符 hex oid。
     * 格式：content = "type size\0body"，oid = SHA1(content)，写入 .git/objects/xx/yyyy...
     */
    public String store(GitObject object) throws IOException {
        byte[] body = object.toBytes();
        String header = object.getType() + " " + body.length + "\0";
        byte[] headerBytes = header.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] content = new byte[headerBytes.length + body.length];
        System.arraycopy(headerBytes, 0, content, 0, headerBytes.length);
        System.arraycopy(body, 0, content, headerBytes.length, body.length);

        String oid = HexUtils.bytesToHex(sha1(content));
        Path objectPath = objectPath(oid);

        log.debug("store type={} oid={} path={}", object.getType(), oid, objectPath);
        // 如果对象已存在，直接返回 oid，避免重复保存
        if (Files.exists(objectPath)) {
            log.debug("store type={} oid={} already exists, skipping", object.getType(), oid);
            return oid;
        }
        Path dir = objectPath.getParent();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        Path temp = dir.resolve("tmp_obj_" + System.nanoTime());
        try {
            byte[] compressed = deflate(content);
            Files.write(temp, compressed);
            Files.move(temp, objectPath, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return oid;
    }

    /**
     * 读取对象，按 obj 文件类型解析为 commit、tree 或 blob 并返回。
     */
    public GitObject load(String oid) throws IOException {
        Path p = objectPath(oid);
        log.debug("load oid={} path={}", oid, p);
        if (!Files.exists(p)) {
            throw new IOException("object not found: " + oid);
        }
        byte[] compressed = Files.readAllBytes(p);
        byte[] content = inflate(compressed);
        int nul = indexOf(content);
        if (nul < 0) {
            throw new IOException("invalid object: " + oid);
        }
        String typeSize = new String(content, 0, nul, java.nio.charset.StandardCharsets.UTF_8);
        int space = typeSize.indexOf(' ');
        if (space < 0) {
            throw new IOException("invalid object header: " + oid);
        }
        String type = typeSize.substring(0, space);
        byte[] body = new byte[content.length - nul - 1];
        System.arraycopy(content, nul + 1, body, 0, body.length);
        return parseObject(type, body);
    }

    /**
     *
     * @param treeOid tree对应id
     * @return tree对象
     * @throws IOException 类型不匹配或者不存在的时候抛出
     */
    public Tree loadTree(String treeOid) throws IOException {
        if (treeOid == null) {
            return Tree.emptyTree();
        }
        GitObject obj = this.load(treeOid);
        if (!"tree".equals(obj.getType())) {
            throw new IOException("expected tree, got " + obj.getType() + ": " + treeOid);
        }
        return (Tree) obj;
    }

    /**
     * 按 oid 加载 blob 对象，不存在或类型不匹配时抛出 IOException。
     */
    public Blob loadBlob(String blobOid) throws IOException {
        GitObject obj = this.load(blobOid);
        if (!"blob".equals(obj.getType())) {
            throw new IOException("expected blob: " + blobOid);
        }
        return (Blob) obj;
    }

    /**
     * 按 oid 加载 commit 对象，使用成员缓存避免重复加载；不存在或类型非 commit 时返回 null。
     */
    public Commit loadCommit(String commitId) {
        if (commitCache.containsKey(commitId)) {
            return commitCache.get(commitId);
        }
        try {
            GitObject obj = this.load(commitId);
            if (!"commit".equals(obj.getType())) {
                return null;
            }
            Commit c = (Commit) obj;
            commitCache.put(commitId, c);
            return c;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 根据类型将 body 解析为 Commit、Tree 或 Blob。
     */
    private static GitObject parseObject(String type, byte[] body) throws IOException {
        return switch (type) {
            case "commit" -> Commit.fromBytes(body);
            case "tree" -> Tree.fromBytes(body);
            case "blob" -> new Blob(body);
            default -> throw new IOException("unknown object type: " + type);
        };
    }

    /**
     * 判断给定 40 字符 hex oid 的对象文件是否存在于 .git/objects 中。
     */
    public boolean exists(String oid) {
        return Files.exists(objectPath(oid));
    }

    /**
     * 按前缀匹配对象 oid，返回所有以 prefix 开头的 40 位 hex oid 列表（排序）。
     * 用于短 SHA1 解析：仅当候选唯一时可解析。
     */
    public List<String> prefixMatch(String prefix) throws IOException {
        if (Strings.isNullOrEmpty(prefix) || prefix.length() > 40) {
            return Collections.emptyList();
        }
        if (!prefix.matches("[0-9a-f]+")) {
            return Collections.emptyList();
        }
        if (!Files.isDirectory(objectsDir)) {
            return Collections.emptyList();
        }
        List<String> oids = new ArrayList<>();
        if (prefix.length() == 1) {
            try (Stream<Path> subdirs = Files.list(objectsDir)) {
                subdirs.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().startsWith(prefix))
                        .forEach(subdir -> collectOidsInDir(oids, subdir, ""));
            }
        } else {
            Path subdir = objectsDir.resolve(prefix.substring(0, 2));
            if (Files.isDirectory(subdir)) {
                String suffix = prefix.length() > 2 ? prefix.substring(2) : "";
                collectOidsInDir(oids, subdir, suffix);
            }
        }
        Collections.sort(oids);
        return oids;
    }

    private void collectOidsInDir(List<String> oids, Path subdir, String filenamePrefix) {
        try (Stream<Path> files = Files.list(subdir)) {
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith(filenamePrefix))
                    .map(name -> subdir.getFileName().toString() + name)
                    .forEach(oids::add);
        } catch (IOException e) {
            // 忽略单目录读取失败
        }
    }

    /**
     * 返回 oid 的短形式（至少 7 位），用于歧义提示等。
     */
    public static String shortOid(String oid) {
        if (Strings.isNullOrEmpty(oid) || oid.length() < 7) {
            return Strings.nullToEmpty(oid);
        }
        return oid.substring(0, 7);
    }

    /**
     * 根据 40 字符 hex oid 得到 .git/objects/xx/yyyy... 路径（前 2 字符为子目录）。
     */
    private Path objectPath(String oid) {
        if (Strings.isNullOrEmpty(oid) || oid.length() < 2) {
            throw new IllegalArgumentException("invalid oid: " + oid);
        }
        return objectsDir.resolve(oid.substring(0, 2)).resolve(oid.substring(2));
    }

    /**
     * 计算输入字节的 SHA-1 摘要（20 字节）。
     */
    private static byte[] sha1(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 使用 zlib deflate 压缩输入字节。
     */
    private static byte[] deflate(byte[] input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream def = new DeflaterOutputStream(out)) {
            def.write(input);
        }
        return out.toByteArray();
    }

    /**
     * 使用 zlib inflate 解压输入字节。
     */
    private static byte[] inflate(byte[] input) throws IOException {
        try (InflaterInputStream inf = new InflaterInputStream(new java.io.ByteArrayInputStream(input));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = inf.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /**
     * 在字节数组中查找第一个等于 b 的下标，未找到返回 -1。
     */
    private static int indexOf(byte[] a) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] == (byte) 0) {
                return i;
            }
        }
        return -1;
    }
}
