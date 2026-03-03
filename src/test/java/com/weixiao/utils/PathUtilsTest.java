package com.weixiao.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PathUtils 测试")
class PathUtilsTest {

    private static String n(String path) {
        return path == null ? null : PathUtils.normalizePath(path);
    }

    @Test
    @DisplayName("getAllParentDir(null) 返回空列表")
    void getAllParentDir_null_returnsEmpty() {
        List<String> result = PathUtils.getAllParentDir(null);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getAllParentDir(空字符串) 返回空列表")
    void getAllParentDir_emptyString_returnsEmpty() {
        assertThat(PathUtils.getAllParentDir("")).isEmpty();
    }

    @Test
    @DisplayName("getAllParentDir(单段路径无父目录) 返回空列表")
    void getAllParentDir_singleSegment_returnsEmpty() {
        assertThat(PathUtils.getAllParentDir("a")).isEmpty();
        assertThat(PathUtils.getAllParentDir("a.txt")).isEmpty();
        assertThat(PathUtils.getAllParentDir("file")).isEmpty();
    }

    @Test
    @DisplayName("getAllParentDir(两段路径) 返回唯一父目录")
    void getAllParentDir_twoSegments_returnsOneParent() {
        List<String> result = PathUtils.getAllParentDir("a/b");
        assertThat(result).hasSize(1);
        assertThat(n(result.get(0))).isEqualTo("a");
    }

    @Test
    @DisplayName("getAllParentDir(多段路径) 按从根到直接父目录顺序返回")
    void getAllParentDir_multiSegment_returnsAllParentsInOrder() {
        List<String> result = PathUtils.getAllParentDir("a/b/c");
        assertThat(result).hasSize(2);
        assertThat(n(result.get(0))).isEqualTo("a");
        assertThat(n(result.get(1))).isEqualTo("a/b");
    }

    @Test
    @DisplayName("getAllParentDir(带文件的路径) 返回所有父目录")
    void getAllParentDir_withFileName_returnsAllParents() {
        List<String> result = PathUtils.getAllParentDir("a/b/c.txt");
        assertThat(result).hasSize(2);
        assertThat(n(result.get(0))).isEqualTo("a");
        assertThat(n(result.get(1))).isEqualTo("a/b");
    }

    @Test
    @DisplayName("getAllParentDir(仅一层子目录+文件) 返回一层父目录")
    void getAllParentDir_oneLevelDirAndFile_returnsOneParent() {
        List<String> result = PathUtils.getAllParentDir("dir/file.txt");
        assertThat(result).hasSize(1);
        assertThat(n(result.get(0))).isEqualTo("dir");
    }

    @Test
    @DisplayName("getAllParentDir(深层路径) 返回所有层级父目录")
    void getAllParentDir_deepPath_returnsAllLevels() {
        List<String> result = PathUtils.getAllParentDir("a/b/c/d/e/f");
        assertThat(result).hasSize(5);
        assertThat(n(result.get(0))).isEqualTo("a");
        assertThat(n(result.get(1))).isEqualTo("a/b");
        assertThat(n(result.get(2))).isEqualTo("a/b/c");
        assertThat(n(result.get(3))).isEqualTo("a/b/c/d");
        assertThat(n(result.get(4))).isEqualTo("a/b/c/d/e");
    }

    @Test
    @DisplayName("getAllParentDir(带正斜杠的路径) 正确解析")
    void getAllParentDir_forwardSlashes_parsedCorrectly() {
        List<String> result = PathUtils.getAllParentDir("foo/bar/baz");
        assertThat(result).hasSize(2);
        assertThat(n(result.get(0))).isEqualTo("foo");
        assertThat(n(result.get(1))).isEqualTo("foo/bar");
    }

    @Test
    @DisplayName("getAllParentDir(仅一个点) 返回空列表")
    void getAllParentDir_singleDot_returnsEmpty() {
        List<String> result = PathUtils.getAllParentDir(".");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getAllParentDir(点点) 返回空列表")
    void getAllParentDir_doubleDot_returnsEmpty() {
        List<String> result = PathUtils.getAllParentDir("..");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getAllParentDir(相对路径含点点) 返回父目录列表")
    void getAllParentDir_pathWithParentRef_returnsParents() {
        List<String> result = PathUtils.getAllParentDir("a/../b/c");
        assertThat(result).isNotEmpty();
    }
}
