package com.weixiao.obj;

import java.util.Arrays;

/**
 * Git blob 对象：表示文件内容。
 * 序列化格式即原始字节（无 header，由 ObjectDatabase 统一加 type + size）。
 */
public final class Blob implements GitObject {

    private final byte[] data;

    /** 用给定字节构造 blob，null 视为空数组并做拷贝避免外部修改。 */
    public Blob(byte[] data) {
        this.data = data != null ? data.clone() : new byte[0];
    }

    /** 返回对象类型 "blob"。 */
    @Override
    public String getType() {
        return "blob";
    }

    /** 返回对象体字节（与 GitObject 约定一致，不含 type/size header）。 */
    @Override
    public byte[] toBytes() {
        return Arrays.copyOf(data, data.length);
    }
}
