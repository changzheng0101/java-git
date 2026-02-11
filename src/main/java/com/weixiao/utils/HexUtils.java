package com.weixiao.utils;

import lombok.experimental.UtilityClass;

/**
 * 十六进制与字节互转工具，用于 oid（40 字符 hex）与 20 字节二进制、SHA-1 输出等。
 */
@UtilityClass
public class HexUtils {

    /**
     * 将 40 字符十六进制字符串转为 20 字节（如 Git oid）。
     * 例："0b" + 38 个 hex → 20 字节。
     *
     * @param hex 40 字符 0-9a-f 字符串
     * @return 20 字节
     */
    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() != 40) {
            throw new IllegalArgumentException("oid must be 40 hex chars, got: " + (hex == null ? "null" : hex.length()));
        }
        byte[] b = new byte[20];
        for (int i = 0; i < 20; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    /**
     * 将字节数组转为小写十六进制字符串（如 SHA-1 的 40 字符 oid）。
     * 例：20 字节 digest → "0b4e..." 共 40 字符。
     *
     * @param bytes 任意长度
     * @return 小写 hex 字符串
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
