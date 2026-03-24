package com.weixiao.model;

import lombok.Data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** status 业务逻辑结果：workspace vs index、index vs HEAD 的差异集合。 */
@Data
public final class StatusResult {

    /**
     * index 处于冲突态的路径集合：
     * key=path，value=该路径存在的冲突 stage 子集（如 [1,2,3]/[1,2]/[1,3]/[2,3]/[2]/[3]）。
     */
    private final Map<String, Set<Integer>> conflicts = new HashMap<>();

    private final Set<String> workspaceModified = new HashSet<>();
    private final Set<String> workspaceDeleted = new HashSet<>();
    private final Set<String> workspaceUntracked = new HashSet<>();
    private final Set<String> indexAdded = new HashSet<>();
    private final Set<String> indexDeleted = new HashSet<>();
    private final Set<String> indexModified = new HashSet<>();

}
