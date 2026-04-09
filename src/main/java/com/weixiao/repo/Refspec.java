package com.weixiao.repo;

import com.google.common.base.Preconditions;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git 风格 refspec，例如 {@code +refs/heads/*:refs/remotes/origin/*}。
 * <p>
 * 前缀 {@code +} 表示强制更新；无 {@code :} 时表示源与目标为同一引用。
 */
public final class Refspec {
    // (\+?)([^:]+):([^:]+)
    static final Pattern REFSPEC_PATTERN = Pattern.compile("(\\+?)([^:]+):([^:]+)");

    @Getter
    private final boolean force;

    @Getter
    private final String source;

    @Getter
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
        Matcher matcher = REFSPEC_PATTERN.matcher(raw.trim());
        if (matcher.matches()) {
            boolean forceUpdate = "+".equals(matcher.group(1));
            String left = matcher.group(2);
            String right = matcher.group(3);
            return new Refspec(forceUpdate, left, right);
        }
        return null;
    }

    /**
     *
     * @return {
     * "refs/int" => ["refs/heads/maint", true],
     * "refs/ster" => ["refs/heads/master", true]
     * }
     * remote => [local,force]
     */
    public static Map<String, RefspecMapping> expand(List<Refspec> refspecs, List<String> remoteRefs) {
        Map<String, RefspecMapping> result = new LinkedHashMap<>();
        for (Refspec spec : refspecs) {
            String src = spec.getSource();
            String dst = spec.getDestination();
            boolean force = spec.isForce();

            // 处理无冒号情况：source 与 destination 相同
            if (dst == null) {
                dst = src;
            }

            // 处理通配符 *
            if (src.contains("*") && dst.contains("*")) {
                String srcPrefix = src.substring(0, src.indexOf('*'));
                String dstPrefix = dst.substring(0, dst.indexOf('*'));
                String srcSuffix = src.substring(src.indexOf('*') + 1);
                String dstSuffix = dst.substring(dst.indexOf('*') + 1);

                for (String remoteRef : remoteRefs) {
                    if (remoteRef.startsWith(srcPrefix) && remoteRef.endsWith(srcSuffix)) {
                        String wildcardPart = remoteRef.substring(srcPrefix.length(), remoteRef.length() - srcSuffix.length());
                        String localRef = dstPrefix + wildcardPart + dstSuffix;
                        result.put(remoteRef, new RefspecMapping(localRef, force));
                    }
                }
            } else {
                // 无通配符，直接匹配
                for (String remoteRef : remoteRefs) {
                    if (remoteRef.equals(src)) {
                        result.put(remoteRef, new RefspecMapping(dst, force));
                    }
                }
            }
        }
        return result;
    }

    record RefspecMapping(String localRef, boolean force) {

    }
}
