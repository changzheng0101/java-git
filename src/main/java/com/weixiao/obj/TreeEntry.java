package com.weixiao.obj;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Objects;

/**
 * Tree 中的一条记录：模式 + 路径 + blob/tree 的 oid。
 * mode 格式：第一位为文件类型（1=regular file），后三位为权限（如 644, 755），应从文件权限读取。
 */
@Getter
@RequiredArgsConstructor
public final class TreeEntry {

    private final String mode;
    private final String name;
    private final String oid; // 40 字符 hex

    @SuppressWarnings("unused")
    public boolean isRegularFile() {
        return mode.startsWith("1");
    }

    public boolean isDirectory() {
        return mode.startsWith("4");
    }

    /**
     * 判断两个条目的 mode 和 oid 是否一致（忽略 name）。
     */
    public static boolean sameModeAndOid(TreeEntry a, TreeEntry b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(a.getMode(), b.getMode())
            && Objects.equals(a.getOid(), b.getOid());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof TreeEntry other)) {
            return false;
        }
        return Objects.equals(mode, other.mode)
            && Objects.equals(name, other.name)
            && Objects.equals(oid, other.oid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, name, oid);
    }
}
