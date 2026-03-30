package com.weixiao.utils;

import lombok.Getter;
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
     * 一行文本：{@code lineNum} 为在所属文档中的从 0 开始的行索引，{@code content} 为行内容（可含末尾换行）。
     */
    public record Line(int lineNum, String content) {

        public Line {
            content = content != null ? content : "";
        }

        /**
         * 表示该侧无对应行（如 INS 无 a 侧、DEL 无 b 侧）。
         */
        public static Line absent() {
            return new Line(-1, "");
        }

    }

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
     * 单条编辑：类型 + a/b 两侧 {@link Line}（无行的一侧为 {@link Line#absent()}）。
     */
    @Getter
    public static final class Edit {
        private final EditType type;
        /**
         * a 侧行（含行号）；无则为absent
         */
        private final Line lineA;
        /**
         * b 侧行（含行号）；无则为absent
         */
        private final Line lineB;

        public Edit(EditType type, Line lineA, Line lineB) {
            this.type = type;
            this.lineA = lineA != null ? lineA : Line.absent();
            this.lineB = lineB != null ? lineB : Line.absent();
        }

        /**
         * 便捷构造：{@code line} 携带真实 {@link Line#lineNum()} 与内容。
         * EQL 两侧均为该 {@link Line}；INS 仅 b 侧；DEL 仅 a 侧；另一侧为 {@link Line#absent()}。
         */
        public Edit(EditType type, Line line) {
            this.type = type;
            this.lineA = switch (type) {
                case INS -> Line.absent();
                case EQL, DEL -> line;
            };
            this.lineB = switch (type) {
                case DEL -> Line.absent();
                case EQL, INS -> line;
            };
        }

        /**
         * 便于沿用旧代码：EQL 返回 a 侧内容，INS 返回 b，DEL 返回 a。
         */
        public String getLine() {
            return switch (type) {
                case EQL -> lineA.content();
                case INS -> lineB.content();
                case DEL -> lineA.content();
            };
        }

        /**
         * 类似 diff 输出：空格 / + / - 加上行内容（行尾无换行则补换行以便对齐）
         */
        @Override
        public String toString() {
            String prefix = type == EditType.EQL ? " " : (type == EditType.INS ? "+" : "-");
            return prefix + getLine();
        }
    }

    /**
     * 将 {@link String} 列表按索引封装为 {@link Line}（行号 0..n-1）。
     */
    public static List<Line> numberedLines(List<String> contents) {
        List<Line> out = new ArrayList<>(contents.size());
        for (int i = 0; i < contents.size(); i++) {
            String s = contents.get(i);
            out.add(new Line(i, s != null ? s : ""));
        }
        return out;
    }

    /**
     * 将文档按行切分为 {@link Line} 列表；{@code lineNum} 为从 0 递增。
     * 入参为 {@link String} 时按换行切分（行串含 {@code \n}）；为 {@link List} 时若元素为 {@link Line} 则原样复制列表，
     * 否则视为已分行字符串列表并按下标编号。
     */
    public static List<Line> lines(Object document) {
        if (document instanceof String s) {
            List<Line> result = new ArrayList<>();
            int start = 0;
            int row = 0;
            for (int i = 0; i <= s.length(); i++) {
                if (i == s.length()) {
                    if (start < i) {
                        result.add(new Line(row, s.substring(start)));
                    }
                    break;
                }
                if (s.charAt(i) == '\n') {
                    result.add(new Line(row, s.substring(start, i + 1)));
                    row++;
                    start = i + 1;
                }
            }
            return result;
        }
        if (document instanceof List<?> list) {
            if (!list.isEmpty() && list.get(0) instanceof Line) {
                List<Line> copy = new ArrayList<>(list.size());
                for (Object o : list) {
                    copy.add((Line) o);
                }
                return copy;
            }
            List<Line> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                Object o = list.get(i);
                String line = o instanceof String str ? str : String.valueOf(o);
                out.add(new Line(i, line != null ? line : ""));
            }
            return out;
        }
        throw new IllegalArgumentException("document must be String or List");
    }

    /**
     * 对两个序列（可为 String 或 List）做 Myers diff，返回编辑列表（从 a 到 b 的顺序）。
     */
    public static List<Edit> diff(Object a, Object b) {
        List<Line> aa = lines(a);
        List<Line> bb = lines(b);
        return diffLines(aa, bb);
    }

    /**
     * 对两段已带行号的 {@link Line} 序列做 diff。
     */
    public static List<Edit> diffLines(List<Line> a, List<Line> b) {
        int n = a.size();
        int m = b.size();
        if (n == 0 && m == 0) {
            return new ArrayList<>();
        }
        int max = n + m;
        int[] v = new int[2 * max + 1];
        List<int[]> trace = new ArrayList<>();

        v[max] = 0;
        for (int d = 0; d <= max; d++) {
            int[] copy = new int[v.length];
            System.arraycopy(v, 0, copy, 0, v.length);
            trace.add(copy);

            for (int k = -d; k <= d; k += 2) {
                int idx = k + max;
                int x;
                if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
                    x = v[idx + 1];
                } else {
                    x = v[idx - 1] + 1;
                }
                int y = x - k;
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

    private static boolean eq(Line s1, Line s2) {
        return s1.content().equals(s2.content());
    }

    private static List<Edit> backtrack(List<Line> a, List<Line> b, List<int[]> trace) {
        List<Edit> diff = new ArrayList<>();
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

            while (x > prevX && y > prevY) {
                diff.add(new Edit(EditType.EQL, a.get(x - 1), b.get(y - 1)));
                x--;
                y--;
            }
            if (d > 0) {
                if (x == prevX) {
                    diff.add(new Edit(EditType.INS, Line.absent(), b.get(prevY)));
                } else if (y == prevY) {
                    diff.add(new Edit(EditType.DEL, a.get(prevX), Line.absent()));
                } else {
                    diff.add(new Edit(EditType.EQL, a.get(prevX), b.get(prevY)));
                }
            }
            x = prevX;
            y = prevY;
        }
        Collections.reverse(diff);
        return diff;
    }
}
