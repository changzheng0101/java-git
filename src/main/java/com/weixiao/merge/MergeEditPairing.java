package com.weixiao.merge;

import com.weixiao.utils.DiffUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 合并后与两侧差异的对齐工具：输入 {@code diff(左, merged)} 与 {@code diff(右, merged)} 的编辑序列，
 * 按 merged（b 侧）行号对齐为 {@link PairedMergeEdit} 列表。
 * <p>
 * 两侧 diff 均以 merged 为第二个输入，因此 EQL/INS 的 {@link DiffUtils.Edit#getLineB()} 对应 merged 中的行，可按 b 侧行号配对。
 * 两侧均为 {@link DiffUtils.EditType#DEL} 且 {@link DiffUtils.Edit#getLineA()} 相等时合并为一对；否则 DEL 优先单独成对，另一侧为 {@code null}。
 */
public final class MergeEditPairing {

    private MergeEditPairing() {
    }

    /**
     * 一对编辑：前者来自左序列（如 ours→merged），后者来自右序列（如 theirs→merged），任一侧可为 {@code null}。
     */
    public record PairedMergeEdit(DiffUtils.Edit leftEdit, DiffUtils.Edit rightEdit) {
    }

    /**
     * 将左、右两侧到 merged 的编辑序列按 merged 的 b 侧对齐。
     * 对称 DEL（a 侧行相同）合并为一对；其余 DEL 单独输出为 (edit, null) 或 (null, edit)，再处理其余编辑。
     *
     * @param leftToMergedEdits  {@code diff(left, merged)} 的结果
     * @param rightToMergedEdits {@code diff(right, merged)} 的结果
     * @return 按 b 侧对齐后的成对编辑列表（不可变视图由调用方自行包装若需要）
     */
    public static List<PairedMergeEdit> pairByMergedBLine(
            List<DiffUtils.Edit> leftToMergedEdits,
            List<DiffUtils.Edit> rightToMergedEdits
    ) {
        List<PairedMergeEdit> out = new ArrayList<>();
        int leftIdx = 0;
        int rightIdx = 0;
        int nL = leftToMergedEdits.size();
        int nR = rightToMergedEdits.size();

        while (leftIdx < nL || rightIdx < nR) {
            DiffUtils.Edit leftEdit = leftIdx < nL ? leftToMergedEdits.get(leftIdx) : null;
            DiffUtils.Edit rightEdit = rightIdx < nR ? rightToMergedEdits.get(rightIdx) : null;

            if (leftEdit == null && rightEdit == null) {
                break;
            }

            // 考虑情况类似 abc -> ac <- abc
            if ((leftEdit != null && leftEdit.getType() == DiffUtils.EditType.DEL) &&
                    (rightEdit != null && rightEdit.getType() == DiffUtils.EditType.DEL) &&
                    leftEdit.getLineA().equals(rightEdit.getLineA())) {
                out.add(new PairedMergeEdit(leftEdit, rightEdit));
                leftIdx++;
                rightIdx++;
                continue;
            }
            if ((leftEdit != null && leftEdit.getType() == DiffUtils.EditType.DEL) || rightEdit == null) {
                out.add(new PairedMergeEdit(leftEdit, null));
                leftIdx++;
                continue;
            }
            if (rightEdit.getType() == DiffUtils.EditType.DEL || leftEdit == null) {
                out.add(new PairedMergeEdit(null, rightEdit));
                rightIdx++;
                continue;
            }


            DiffUtils.Line bL = leftEdit.getLineB();
            DiffUtils.Line bR = rightEdit.getLineB();
            int leftMergeFileLineNum = bL.lineNum();
            int rightMergeFileLineNum = bR.lineNum();
            if (leftMergeFileLineNum == rightMergeFileLineNum) {
                out.add(new PairedMergeEdit(leftEdit, rightEdit));
                leftIdx++;
                rightIdx++;
            } else if (leftMergeFileLineNum < rightMergeFileLineNum) {
                out.add(new PairedMergeEdit(leftEdit, null));
                leftIdx++;
            } else {
                out.add(new PairedMergeEdit(null, rightEdit));
                rightIdx++;
            }
        }

        return out;
    }
}
