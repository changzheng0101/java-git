package com.weixiao.utils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 加密摘要工具。 */
public final class CryptoUtils {

    private CryptoUtils() {
    }

    /** 计算输入字节的 SHA-1 摘要（20 字节）。 */
    public static byte[] sha1(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
