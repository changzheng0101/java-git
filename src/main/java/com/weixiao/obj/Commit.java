package com.weixiao.obj;

import lombok.Getter;

import java.nio.charset.StandardCharsets;

/**
 * Git commit 对象：tree、author、committer、message。
 * 序列化格式与 Git 一致：tree &lt;oid&gt;\nauthor ...\ncommitter ...\n\nmessage
 */
@Getter
public final class Commit implements GitObject {

    private final String treeOid;
    private final String parentOid; // 可选，首次提交为 null
    private final String author;
    private final String committer;
    private final String message;

    /**
     * 用 tree、父提交、作者、提交者、提交信息构造 commit；parentOid 可为 null 表示首次提交，message 为 null 时当作空字符串。
     */
    public Commit(String treeOid, String parentOid, String author, String committer, String message) {
        this.treeOid = treeOid;
        this.parentOid = parentOid;
        this.author = author;
        this.committer = committer;
        this.message = message != null ? message : "";
    }

    /**
     * 构造首次提交（无 parent），author 同时作为 committer。
     */
    public static Commit first(String treeOid, String author, String message) {
        return new Commit(treeOid, null, author, author, message);
    }

    /**
     * 从对象体字节解析出 commit，格式：tree &lt;oid&gt;\n[parent &lt;oid&gt;\n]author ...\ncommitter ...\n\nmessage。
     */
    public static Commit fromBytes(byte[] body) {
        String raw = new String(body, StandardCharsets.UTF_8);
        int headerEnd = raw.indexOf("\n\n");
        if (headerEnd < 0) {
            throw new IllegalArgumentException("invalid commit: missing header/message separator");
        }
        String header = raw.substring(0, headerEnd);
        String message = raw.substring(headerEnd + 2);
        String treeOid = null;
        String parentOid = null;
        String author = null;
        String committer = null;
        for (String line : header.split("\n")) {
            if (line.startsWith("tree ")) {
                treeOid = line.substring(5).trim();
            } else if (line.startsWith("parent ")) {
                if (parentOid == null) {
                    parentOid = line.substring(7).trim();
                }
            } else if (line.startsWith("author ")) {
                author = line.substring(7);
            } else if (line.startsWith("committer ")) {
                committer = line.substring(10);
            }
        }
        if (treeOid == null || author == null || committer == null) {
            throw new IllegalArgumentException("invalid commit: missing tree/author/committer");
        }
        return new Commit(treeOid, parentOid, author, committer, message);
    }

    /**
     * 取字符串首行（到第一个换行或结尾），null 返回空串。用于 commit message 等。
     */
    public static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i >= 0 ? s.substring(0, i).trim() : s.trim();
    }

    /**
     * 返回对象类型 "commit"。
     */
    @Override
    public String getType() {
        return "commit";
    }

    /**
     * 返回 Git commit 格式字节：tree、可选的 parent、author、committer、空行、message。
     */
    @Override
    public byte[] toBytes() {
        StringBuilder sb = new StringBuilder();
        sb.append("tree ").append(treeOid).append("\n");
        if (parentOid != null) sb.append("parent ").append(parentOid).append("\n");
        sb.append("author ").append(author).append("\n");
        sb.append("committer ").append(committer).append("\n");
        sb.append("\n");
        sb.append(message);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
