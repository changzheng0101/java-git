package com.weixiao.obj;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

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
}
