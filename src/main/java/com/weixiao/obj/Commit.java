package com.weixiao.obj;

import java.nio.charset.StandardCharsets;

/**
 * Git commit 对象：tree、author、committer、message。
 * 序列化格式与 Git 一致：tree &lt;oid&gt;\nauthor ...\ncommitter ...\n\nmessage
 */
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
