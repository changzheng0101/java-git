package com.weixiao.model;

/**
 * 单文件在 index vs workspace 或 index vs HEAD tree 下的状态。
 */
public enum FileStatus {
    /**
     * 内容或 mode 与对方不同
     */
    MODIFIED,
    /**
     * 仅在己方存在（如仅 index 有则为 index added）
     */
    ADDED,
    /**
     * 仅在对方存在（如仅 HEAD 有则为 index deleted）
     */
    DELETED,
    /**
     * 双方一致
     */
    UNCHANGED
}
