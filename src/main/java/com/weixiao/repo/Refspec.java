package com.weixiao.repo;

import com.google.common.base.Preconditions;

/**
 * Git 风格 refspec，例如 {@code +refs/heads/*:refs/remotes/origin/*}。
 * <p>
 * 前缀 {@code +} 表示强制更新；无 {@code :} 时表示源与目标为同一引用。
 */
public final class Refspec {

    private final boolean force;

    private final String source;

    private final String destination;

    private Refspec(boolean force, String source, String destination) {
        this.force = force;
        this.source = Preconditions.checkNotNull(source);
        this.destination = destination;
    }

    /**
     * 解析单行 refspec 字符串（如 config 中 {@code remote.*.fetch} 的值）。
     */
    public static Refspec parse(String raw) {
        Preconditions.checkNotNull(raw);
        String s = raw.trim();
        Preconditions.checkArgument(!s.isEmpty(), "empty refspec");
        boolean forceUpdate = false;
        if (s.startsWith("+")) {
            forceUpdate = true;
            s = s.substring(1).trim();
            Preconditions.checkArgument(!s.isEmpty(), "empty refspec after '+'");
        }
        int colon = s.indexOf(':');
        if (colon < 0) {
            return new Refspec(forceUpdate, s, null);
        }
        String left = s.substring(0, colon).trim();
        String right = s.substring(colon + 1).trim();
        return new Refspec(forceUpdate, left, right.isEmpty() ? null : right);
    }

    public boolean force() {
        return force;
    }

    public String source() {
        return source;
    }

    /**
     * 无 {@code :} 右侧时为 null，表示与 {@link #source()} 相同。
     */
    public String destination() {
        return destination;
    }
}
