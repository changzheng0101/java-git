package com.weixiao.merge;

import com.weixiao.utils.DiffUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.*;

/**
 * 三方合并（diff3）：输入 base/ours/theirs 三份文档（按 {@link DiffUtils.Line}），输出合并结果与冲突块。
 */
public record Diff3(List<DiffUtils.Line> base, List<DiffUtils.Line> ours, List<DiffUtils.Line> theirs) {

    /**
     * 合并入口：支持 {@link String}、{@link List}（元素为行串）或 {@link List}{@code <}{@link DiffUtils.Line}{@code >}。
     */
    public static MergeFileResult merge(Object original, Object ours, Object theirs) {
        return new Diff3(
                DiffUtils.lines(original),
                DiffUtils.lines(ours),
                DiffUtils.lines(theirs)).merge();
    }

    private MergeFileResult merge() {
        List<DiffUtils.Edit> editsOurs = DiffUtils.diffLines(base, ours);
        List<DiffUtils.Edit> editsTheirs = DiffUtils.diffLines(base, theirs);
        return new MergeFileResult(generateChunks(editsOurs, editsTheirs));
    }

    /**
     * 根据 base→ours、base→theirs 两份 edit 序列，在 base 行坐标上对齐区间并生成 Chunk。
     * <p>
     * 每个 Chunk 保存该区间在 base/ours/theirs 三侧的行视图及合并类型（edits 仅用于此处切片与推导）。
     */
    private List<Chunk> generateChunks(List<DiffUtils.Edit> editsOurs, List<DiffUtils.Edit> editsTheirs) {
        // 构造相同line缓存
        Map<Integer, Integer> baseToOurSameLineNum = new HashMap<>();
        Map<Integer, Integer> baseToTheirSameLineNum = new HashMap<>();
        for (DiffUtils.Edit edit : editsOurs) {
            if (edit.getType() == DiffUtils.EditType.EQL) {
                baseToOurSameLineNum.put(edit.getLineA().lineNum(), edit.getLineB().lineNum());
            }
        }
        for (DiffUtils.Edit edit : editsTheirs) {
            if (edit.getType() == DiffUtils.EditType.EQL) {
                baseToTheirSameLineNum.put(edit.getLineA().lineNum(), edit.getLineB().lineNum());
            }
        }

        // 构建chunk
        List<Chunk> chunks = new ArrayList<>();
        LineCursor cursor = new LineCursor();

        while (true) {
            int disFromCurPos = findNextMismatch(cursor, baseToOurSameLineNum, baseToTheirSameLineNum);
            if (disFromCurPos == 0) {
                LineCursor nextCursor = findNextMatch(cursor, baseToOurSameLineNum, baseToTheirSameLineNum);
                chunks.add(getChunk(cursor, nextCursor));
                if (nextCursor.outOfRange()) {
                    break;
                }
                cursor = nextCursor;
            } else {
                chunks.add(getChunk(cursor, disFromCurPos));
                if (disFromCurPos == -1) {
                    break;
                }
                cursor.move(disFromCurPos);
            }
        }

        return chunks;
    }

    private Chunk getChunk(LineCursor cursor, LineCursor nextCursor) {
        int nextBase = nextCursor.baseIdx == -1 ? this.base.size() : nextCursor.baseIdx;
        int nextOur = nextCursor.ourIdx == -1 ? this.ours.size() : nextCursor.ourIdx;
        int nextTheir = nextCursor.theirIdx == -1 ? this.theirs.size() : nextCursor.theirIdx;

        return new Chunk(
                this.base.subList(cursor.baseIdx, nextBase),
                this.ours.subList(cursor.ourIdx, nextOur),
                this.theirs.subList(cursor.theirIdx, nextTheir)
        );
    }

    private Chunk getChunk(LineCursor cursor, int i) {
        if (i == -1) {
            return new Chunk(
                    this.base.subList(cursor.baseIdx, this.base.size()),
                    this.ours.subList(cursor.ourIdx, this.ours.size()),
                    this.theirs.subList(cursor.theirIdx, this.theirs.size())
            );
        }
        return new Chunk(
                this.base.subList(cursor.baseIdx, cursor.baseIdx + i),
                this.ours.subList(cursor.ourIdx, cursor.ourIdx + i),
                this.theirs.subList(cursor.theirIdx, cursor.theirIdx + i)
        );
    }

    /**
     * 追踪 base、ours、theirs 三份文档的当前行索引。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static final class LineCursor {
        int ourIdx;
        int baseIdx;
        int theirIdx;

        public boolean outOfRange() {
            return baseIdx == -1 || ourIdx == -1 || theirIdx == -1;
        }

        public void move(int disFromCurPos) {
            this.baseIdx += disFromCurPos;
            this.ourIdx += disFromCurPos;
            this.theirIdx += disFromCurPos;
        }
    }

    /**
     * 当前状态需求是mismatch
     *
     * @return 下一个LineCursor 代表离传入位置match最近位置的line cursor
     */
    private LineCursor findNextMatch(LineCursor cursor, Map<Integer, Integer> baseToOurSameLineNum, Map<Integer, Integer> baseToTheirSameLineNum) {
        for (int i = cursor.baseIdx; i < this.base.size(); i++) {
            if (baseToOurSameLineNum.containsKey(i) && baseToTheirSameLineNum.containsKey(i)) {
                return new LineCursor(i, baseToOurSameLineNum.get(i), baseToTheirSameLineNum.get(i));
            }
        }
        return new LineCursor(-1, -1, -1);
    }

    /**
     *
     * 如果cursor处在match位置，剩下的会都匹配，所以可以用+i的方式访问三个文件
     *
     * @param cursor 调用的时候已经处在match的位置
     * @return 到下一个Mismatch的长度 如果到末尾还未找到，返回-1
     */
    private int findNextMismatch(LineCursor cursor, Map<Integer, Integer> baseToOurSameLineNum, Map<Integer, Integer> baseToTheirSameLineNum) {
        int i = 0;
        while (inRange(cursor, i) && baseToOurSameLineNum.containsKey(cursor.baseIdx + i)
                && baseToTheirSameLineNum.containsKey(cursor.baseIdx + i)) {
            i++;
        }
        return inRange(cursor, i) ? i : -1;
    }

    private boolean inRange(LineCursor cursor, int i) {
        return cursor.getOurIdx() + i < this.ours.size() &&
                cursor.getBaseIdx() + i < this.base.size() &&
                cursor.getTheirIdx() + i < this.theirs.size();
    }

    public enum ChunkType {
        /**
         * 可直接自动合并，不产生冲突标记。并不是代表base、our、their对应的资源相同
         */
        CLEAN_MERGE,
        CONFLICT
    }

    /**
     * 两个 {@link DiffUtils.Line} 列表是否相等：先比较 size，再逐元素 {@link DiffUtils.Line#equals}。
     */
    private static boolean linesEqual(List<DiffUtils.Line> a, List<DiffUtils.Line> b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static void appendLines(StringBuilder out, List<DiffUtils.Line> lines) {
        for (DiffUtils.Line line : lines) {
            out.append(line.content());
        }
    }

    private static void ensureEndWithNewline(StringBuilder out) {
        if (out.isEmpty() || out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    /**
     * 一个合并片段：同一 base 行区间上的 base/ours/theirs 行视图与合并类型。
     */
    @Getter
    public static final class Chunk {
        private final ChunkType chunkType;
        private final List<DiffUtils.Line> baseLines;
        private final List<DiffUtils.Line> oursLines;
        private final List<DiffUtils.Line> theirsLines;


        private Chunk(List<DiffUtils.Line> baseLines, List<DiffUtils.Line> oursLines, List<DiffUtils.Line> theirsLines) {
            if (baseLines.isEmpty() || oursLines.isEmpty() || theirsLines.isEmpty()) {
                this.chunkType = ChunkType.CLEAN_MERGE;
            } else if (linesEqual(baseLines, theirsLines) || linesEqual(baseLines, oursLines) || linesEqual(oursLines, theirsLines)) {
                this.chunkType = ChunkType.CLEAN_MERGE;
            } else {
                this.chunkType = ChunkType.CONFLICT;
            }

            this.baseLines = baseLines;
            this.oursLines = oursLines;
            this.theirsLines = theirsLines;
        }

        /**
         * 获取该 chunk 的合并输出文本：
         * - CLEAN：返回自动决议后的文本
         * - CONFLICT：返回带冲突标记的文本
         */
        public String mergedText(String oursName, String theirsName) {
            if (chunkType == ChunkType.CLEAN_MERGE) {
                int n = Math.max(Math.max(oursLines.size(), baseLines.size()), theirsLines.size());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    String o = i < oursLines.size() ? oursLines.get(i).content() : "";
                    String b = i < baseLines.size() ? baseLines.get(i).content() : "";
                    String t = i < theirsLines.size() ? theirsLines.get(i).content() : "";
                    sb.append(o.equals(b) ? t : o);
                }
                return sb.toString();
            }

            StringBuilder out = new StringBuilder();
            out.append("<<<<<<< ").append(oursName).append('\n');
            appendLines(out, oursLines);
            ensureEndWithNewline(out);
            out.append("=======\n");
            appendLines(out, theirsLines);
            ensureEndWithNewline(out);
            out.append(">>>>>>> ").append(theirsName).append('\n');
            return out.toString();
        }
    }

    /**
     * Diff3 合并结果：可渲染为文本并指示是否包含冲突。
     */
    public record MergeFileResult(List<Chunk> chunks) {
        public MergeFileResult(List<Chunk> chunks) {
            this.chunks = List.copyOf(chunks);
        }

        public boolean hasConflicts() {
            return chunks.stream().anyMatch(chunk -> chunk.getChunkType() == ChunkType.CONFLICT);
        }

        /**
         * 渲染合并结果（冲突块使用 Git 风格标记）。
         */
        public String render(String oursName, String theirsName) {
            StringBuilder out = new StringBuilder();
            for (Chunk chunk : chunks) {
                out.append(chunk.mergedText(oursName, theirsName));
            }
            return out.toString();
        }
    }
}
