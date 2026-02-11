package com.weixiao.obj;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Blob 测试")
class BlobTest {

    /**
     * Blob 的 getType 为 "blob"，toBytes() 返回与构造时传入的字节一致。
     * 示例：Blob("hello world".getBytes()) → getType()="blob"，toBytes() 等于 "hello world" 的 UTF-8 字节。
     */
    @Test
    @DisplayName("toBytes 返回与构造一致的字节")
    void toBytes_returnsSameData(@TempDir Path dir) {
        byte[] data = "hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Blob blob = new Blob(data);
        assertThat(blob.getType()).isEqualTo("blob");
        assertThat(blob.toBytes()).isEqualTo("hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
