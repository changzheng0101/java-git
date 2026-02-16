package com.weixiao.utils;

import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于 Myers O(ND) 算法的 diff 工具。
 * 参考论文 "An O(ND) Difference Algorithm and its Variations"，
 * 对两段文本（按行）求最短编辑脚本，得到 equal/insert/delete 序列。
 */
@UtilityClass
public class DiffUtils {

    /**
     * 编辑类型：相等、插入、删除
     */
    public enum EditType {
        /**
         * 两行相同
         */
        EQL,
        /**
         * 在 b 中新增的一行
         */
        INS,
        /**
         * 在 a 中删除的一行
         */
        DEL
    }

    /**
     * 单条编辑：类型 + 行内容（insert/delete 时仅一侧有内容）
     */
    public static final class Edit {
        private final EditType type;
        private final String line;

        public Edit(EditType type, String line) {
            this.type = type;
            this.line = line != null ? line : "";
        }

        public EditType getType() {
            return type;
        }

        public String getLine() {
            return line;
        }

        /**
         * 类似 diff 输出：空格 / + / - 加上行内容（行尾无换行则补换行以便对齐）
         */
        @Override
        public String toString() {
            String prefix = type == EditType.EQL ? " " : (type == EditType.INS ? "+" : "-");
            return prefix + line;
        }
    }

    /**
     * 将文档按行切分；若已是行列表则按字符串比较。
     */
    public static List<String> lines(Object document) {
        if (document instanceof String) {
            String s = (String) document;
            List<String> result = new ArrayList<>();
            int start = 0;
            for (int i = 0; i <= s.length(); i++) {
                if (i == s.length()) {
                    if (start < i) result.add(s.substring(start));
                    break;
                }
                if (s.charAt(i) == '\n') {
                    result.add(s.substring(start, i + 1)); // 含换行
                    start = i + 1;
                }
            }
            return result;
        }
        if (document instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) document;
            return new ArrayList<>(list);
        }
        throw new IllegalArgumentException("document must be String or List<String>");
    }

    /**
     * 对两个序列（可为 String 或 List&lt;String&gt;）做 Myers diff，返回编辑列表（从 a 到 b 的顺序）。
     */
    public static List<Edit> diff(Object a, Object b) {
        List<String> aa = lines(a);
        List<String> bb = lines(b);
        return diffLines(aa, bb);
    }

    /**
     * 对两段已按行拆分的文本做 diff。
     */
    public static List<Edit> diffLines(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        if (n == 0 && m == 0) return new ArrayList<>();
        int max = n + m;
        // V[k] 表示在对角线 k 上能到达的最大 x，k = x - y
        // 在数组中的索引 index = k + max ,k 的取值范围 [-max , max]
        int[] v = new int[2 * max + 1];
        // 存储每一步执行情况 里面的index对应移动步数，例如移动0步，trace.get(0)
        List<int[]> trace = new ArrayList<>();

        v[max] = 0; // k=0 时初始 x=0
        for (int d = 0; d <= max; d++) {
            int[] copy = new int[v.length];
            System.arraycopy(v, 0, copy, 0, v.length);
            trace.add(copy);

            for (int k = -d; k <= d; k += 2) {
                int idx = k + max;
                int x;
                if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
                    x = v[idx + 1]; // 从下方来（insert）
                } else {
                    x = v[idx - 1] + 1; // 从左边来（delete）
                }
                int y = x - k;
                // 沿对角线走 snake
                while (x < n && y < m && eq(a.get(x), b.get(y))) {
                    x++;
                    y++;
                }
                v[idx] = x;
                if (x >= n && y >= m) {
                    return backtrack(a, b, trace);
                }
            }
        }
        return backtrack(a, b, trace);
    }

    private static boolean eq(String s1, String s2) {
        return (s1 == null && s2 == null) || (s1 != null && s1.equals(s2));
    }

    private static List<Edit> backtrack(List<String> a, List<String> b,
                                        List<int[]> trace) {
        List<Edit> diff = new ArrayList<>();
        // 记录当前位置 理论上已经到达右下角
        int x = a.size();
        int y = b.size();
        int max = x + y;

        for (int d = trace.size() - 1; d >= 0; d--) {
            int[] v = trace.get(d);
            int k = x - y;
            int idx = k + max;
            int prevK;
            if (k == -d) {
                prevK = k + 1;
            } else if (k == d) {
                prevK = k - 1;
            } else if (v[idx - 1] < v[idx + 1]) {
                prevK = k + 1;
            } else {
                prevK = k - 1;
            }
            int prevX = v[prevK + max];
            int prevY = prevX - prevK;

            // 沿对角线回退（相等段）
            while (x > prevX && y > prevY) {
                diff.add(new Edit(EditType.EQL, a.get(x - 1)));
                x--;
                y--;
            }
            if (d > 0) {
                if (x == prevX) {
                    diff.add(new Edit(EditType.INS, b.get(prevY)));
                } else if (y == prevY) {
                    diff.add(new Edit(EditType.DEL, a.get(prevX)));
                } else {
                    diff.add(new Edit(EditType.EQL, a.get(prevX)));
                }
            }
            x = prevX;
            y = prevY;
        }
        Collections.reverse(diff);
        return diff;
    }
}
