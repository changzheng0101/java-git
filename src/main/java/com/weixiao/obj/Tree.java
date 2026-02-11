package com.weixiao.obj;

import com.weixiao.utils.HexUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Git tree 对象：目录快照，条目按 name 排序。
 * 序列化：每条 mode + " " + name + "\0" + 20 字节二进制 oid。
 */
public final class Tree implements GitObject {

    private final List<TreeEntry> entries;

    /** 用给定条目构造 tree，内部按 name 排序；null 或空列表视为空 tree。 */
    public Tree(List<TreeEntry> entries) {
        this.entries = new ArrayList<>(entries != null ? entries : List.of());
        this.entries.sort(Comparator.comparing(TreeEntry::getName));
    }

    /** 返回对象类型 "tree"。 */
    @Override
    public String getType() {
        return "tree";
    }

    /** 返回 Git tree 格式字节：每条 mode + " " + name + "\\0" + 20 字节二进制 oid，条目已按 name 排序。 */
    @Override
    public byte[] toBytes() {
        List<byte[]> parts = new ArrayList<>();
        for (TreeEntry e : entries) {
            byte[] nameBytes = e.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] oidBinary = HexUtils.hexToBytes(e.getOid());
            String header = e.getMode() + " " + e.getName() + "\0";
            byte[] headerBytes = header.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] line = new byte[headerBytes.length + 20];
            System.arraycopy(headerBytes, 0, line, 0, headerBytes.length);
            System.arraycopy(oidBinary, 0, line, headerBytes.length, 20);
            parts.add(line);
        }
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
