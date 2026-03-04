package com.weixiao.repo;

import lombok.Value;

/**
 * 仓库中的 ref 路径封装，例如 HEAD、refs/heads/master 等。
 * 目前仅包含一个 {@code path} 字段，对应原先使用的 string 路径。
 */
@Value
public class SysRef {

    /**
     * ref 在 .git 目录下的相对路径，例如 "HEAD"、"refs/heads/master"。
     */
    String path;
}

