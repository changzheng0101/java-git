package com.weixiao.model;

import lombok.Data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * status 业务逻辑的结果：workspace vs index、index vs HEAD 的差异集合，
 * 以及 HEAD tree 的 path→oid，供 status 展示与 diff 复用。
 */
@Data
public final class StatusResult {

    private final Set<String> workspaceModified = new HashSet<>();
    private final Set<String> workspaceDeleted = new HashSet<>();
    private final Set<String> workspaceUntracked = new HashSet<>();
    private final Set<String> indexAdded = new HashSet<>();
    private final Set<String> indexDeleted = new HashSet<>();
    private final Set<String> indexModified = new HashSet<>();
    /**
     * HEAD 对应 tree 中 path → blob oid，无 HEAD 时为空。
     */
    private final Map<String, String> headPathToOid = new HashMap<>();
    /**
     * HEAD 对应 tree 中 path → mode（如 100644），无 HEAD 时为空。
     */
    private final Map<String, String> headPathToMode = new HashMap<>();

}
