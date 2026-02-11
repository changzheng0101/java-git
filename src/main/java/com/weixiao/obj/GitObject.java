package com.weixiao.obj;

/**
 * Git 对象统一接口：blob、tree、commit。
 * 由 ObjectDatabase 序列化为 "type size\0body" 并写入 .git/objects。
 */
public interface GitObject {

    /** 对象类型：blob / tree / commit */
    String getType();

    /** 对象体字节（不含 type/size header） */
    byte[] toBytes();
}
