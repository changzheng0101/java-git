package com.weixiao.repo;

import com.weixiao.obj.GitObject;
import com.weixiao.utils.HexUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import lombok.Value;

import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * .git/objects 存储：按 oid 写入/读取对象，格式与 Git 一致（type size\0body，zlib 压缩）。
 */
public final class ObjectDatabase {

    private static final String OBJECTS_DIR = "objects";
    private final Path gitDir;

    /**
     * 以 .git 目录为基准，对象存储路径为 .git/objects。
     */
    public ObjectDatabase(Path gitDir) {
        this.gitDir = gitDir.resolve(OBJECTS_DIR);
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
     * 读取对象，返回 (type, body)。
     */
    public RawObject load(String oid) throws IOException {
        Path p = objectPath(oid);
        if (!Files.exists(p)) {
            throw new IOException("object not found: " + oid);
        }
        byte[] compressed = Files.readAllBytes(p);
        byte[] content = inflate(compressed);
        int nul = indexOf(content, (byte) 0);
        if (nul < 0) throw new IOException("invalid object: " + oid);
        String typeSize = new String(content, 0, nul, java.nio.charset.StandardCharsets.UTF_8);
        int space = typeSize.indexOf(' ');
        if (space < 0) throw new IOException("invalid object header: " + oid);
        String type = typeSize.substring(0, space);
        byte[] body = new byte[content.length - nul - 1];
        System.arraycopy(content, nul + 1, body, 0, body.length);
        return new RawObject(type, body);
    }

    /**
     * 判断给定 40 字符 hex oid 的对象文件是否存在于 .git/objects 中。
     */
    public boolean exists(String oid) {
        return Files.exists(objectPath(oid));
    }

    /**
     * 根据 40 字符 hex oid 得到 .git/objects/xx/yyyy... 路径（前 2 字符为子目录）。
     */
    private Path objectPath(String oid) {
        if (oid == null || oid.length() < 2) throw new IllegalArgumentException("invalid oid: " + oid);
        return gitDir.resolve(oid.substring(0, 2)).resolve(oid.substring(2));
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
    private static int indexOf(byte[] a, byte b) {
        for (int i = 0; i < a.length; i++) if (a[i] == b) return i;
        return -1;
    }

    /**
     * 从对象库 load 得到的原始对象：类型（blob/tree/commit）与解压后的 body 字节。
     */
    @Value
    public static class RawObject {
        String type;
        byte[] body;
    }
}
