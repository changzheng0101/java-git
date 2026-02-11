package com.weixiao.obj;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Tree 中的一条记录：模式 + 路径 + blob/tree 的 oid。
 */
@Getter
@RequiredArgsConstructor
public final class TreeEntry {

    public static final String MODE_REGULAR = "100644";

    private final String mode;
    private final String name;
    private final String oid; // 40 字符 hex

    /** 构造一条普通文件条目：mode=100644，name 为路径，oid 为 40 字符 hex。 */
    public static TreeEntry regularFile(String name, String oid) {
        return new TreeEntry(MODE_REGULAR, name, oid);
    }
}
