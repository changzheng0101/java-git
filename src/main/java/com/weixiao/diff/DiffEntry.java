package com.weixiao.diff;

import com.weixiao.obj.TreeEntry;
import lombok.Getter;

/**
 * 单条 tree diff：状态 + A 侧条目 + B 侧条目。
 * CREATED 时 entryA 为 null；DELETED 时 entryB 为 null；MODIFIED 时两者均非 null。
 */
@Getter
public final class DiffEntry {

    private final DiffStatus status;
    /**
     * A 侧（旧）条目，CREATED 时为 null。
     */
    private final TreeEntry entryA;
    /**
     * B 侧（新）条目，DELETED 时为 null。
     */
    private final TreeEntry entryB;

    public DiffEntry(DiffStatus status, TreeEntry entryA, TreeEntry entryB) {
        this.status = status;
        this.entryA = entryA;
        this.entryB = entryB;
    }

    /**
     * 当前条目的名称（任一侧非 null 时取 name）。
     */
    public String getName() {
        return entryB != null ? entryB.getName() : entryA.getName();
    }

    public enum DiffStatus {
        /**
         * B 有、A 无（新创建）。
         */
        CREATED,
        /**
         * A 有、B 无（删除）。
         */
        DELETED,
        /**
         * 两边都有但 oid 或 mode 不同（修改）。
         */
        MODIFIED
    }
}
