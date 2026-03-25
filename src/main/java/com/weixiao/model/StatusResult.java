package com.weixiao.model;

import lombok.Data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * status 业务逻辑结果：workspace vs index、index vs HEAD 的差异集合。
 */
@Data
public final class StatusResult {

    /**
     * index 处于冲突态的路径集合：
     * key=path，value=该路径存在的冲突 stage 子集（如 [1,2,3]/[1,2]/[1,3]/[2,3]/[2]/[3]）。
     */
    private final Map<String, Set<Integer>> conflicts = new HashMap<>();
    /**
     * index 有（stage-0），workspace 有，但内容/模式与 index 不同。
     */
    private final Set<String> workspaceModified = new HashSet<>();
    /**
     * index 有（stage-0），workspace 没有普通文件（不存在或已变为目录）。
     */
    private final Set<String> workspaceDeleted = new HashSet<>();
    /**
     * workspace 有，且 index/冲突集合都没有（即未被跟踪）。
     */
    private final Set<String> workspaceUntracked = new HashSet<>();
    /**
     * index 有（stage-0），HEAD 没有（相对 HEAD 新增到暂存区）。
     */
    private final Set<String> indexAdded = new HashSet<>();
    /**
     * HEAD 有，index 没有 stage-0（相对 HEAD 从暂存区删除）。
     */
    private final Set<String> indexDeleted = new HashSet<>();
    /**
     * HEAD 有，index 有（stage-0），但 oid 或 mode 不同。
     */
    private final Set<String> indexModified = new HashSet<>();

}
