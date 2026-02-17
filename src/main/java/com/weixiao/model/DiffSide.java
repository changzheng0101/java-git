package com.weixiao.model;

import lombok.Data;

import static com.weixiao.command.DiffCommand.NULL_OID;

/**
 * git diff时存储一边的变更
 * diff 一侧的文件信息：oid、mode、content。
 * 用于表示 diff 中 a/ 或 b/ 一侧，oid 为 null 表示该侧无文件（如 /dev/null）。
 */
@Data
public final class DiffSide {
    private final String path;
    private final String oid;
    private final String mode;
    private final String content;

    public DiffSide(String path, String oid, String mode, String content) {
        this.path = path;
        this.oid = oid;
        this.mode = mode;
        this.content = content != null ? content : "";
    }


    /**
     * 该侧是否有文件（oid 非 null 表示有文件，否则为 /dev/null）。
     */
    public boolean hasFile() {
        return oid != NULL_OID;
    }
}
