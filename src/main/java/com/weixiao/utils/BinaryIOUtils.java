package com.weixiao.utils;

import java.io.ByteArrayOutputStream;

/** 二进制 IO 工具：按大端序写入基础数值类型。 */
public final class BinaryIOUtils {

    private BinaryIOUtils() {
    }

    public static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }

    public static void writeShort(ByteArrayOutputStream out, short value) {
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }
}
