package com.weixiao.command;

import com.weixiao.merge.MergeInputs;
import com.weixiao.merge.MergeResolve;
import com.weixiao.repo.*;
import com.weixiao.revision.RevisionParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.util.Arrays;

/**
 * jit merge：将指定分支与当前 HEAD 合并。
 * 当前仅实现一个简化版本：计算 base（BCA）到 merge 分支 tip 的净变化，并把这部分变化应用到当前工作区与 index，
 * 然后从更新后的 index 写一个新的 merge commit（parents=HEAD, merge_tip）。
 */
@Command(name = "merge", mixinStandardHelpOptions = true, description = "合并指定分支到当前 HEAD（简化版：应用 merge 分支净变化并写 merge commit）")
public class MergeCommand extends BaseCommand {

    private static final Logger log = LoggerFactory.getLogger(MergeCommand.class);

    @SuppressWarnings("unused")
    @Parameters(index = "0", arity = "0..1", paramLabel = "REV", description = "要合并的分支或修订")
    private String rev;

    @SuppressWarnings("unused")
    @Option(names = {"--continue"}, description = "继续一个已开始但未完成的 merge")
    private boolean continueMerge;

    @Override
    protected void doRun() {
        try {
            PendingCommit pendingCommit = new PendingCommit(repo.getGitDir());
            if (continueMerge) {
                handleContinue(pendingCommit);
                return;
            }
            if (pendingCommit.inProgress()) {
                System.err.println("error: merge already in progress.");
                exitCode = 1;
                return;
            }
            if (rev == null || rev.isBlank()) {
                System.err.println("fatal: merge target is required.");
                exitCode = 1;
                return;
            }
            MergeInputs inputs = MergeInputs.from(rev);
            if (inputs.getHeadOid() == null || inputs.getHeadOid().isEmpty()) {
                System.err.println("fatal: Not a valid object name: 'HEAD'.");
                exitCode = 1;
                return;
            }
            if (inputs.getKind() == MergeInputs.Kind.UP_TO_DATE) {
                System.out.println("Already up to date.");
                return;
            }

            if (inputs.getKind() == MergeInputs.Kind.FAST_FORWARD) {
                Migration migration = new Migration(inputs.getHeadOid(), inputs.getMergeOid());
                migration.validate();
                migration.applyChanges();
                repo.getRefs().updateCurrentBranch(inputs.getMergeOid());
                System.out.println("Fast-forward");
                return;
            }

            if (inputs.getBaseOid() == null) {
                System.err.println("fatal: no common ancestor found.");
                exitCode = 1;
                return;
            }

            String mergeMsg = "Merge branch '" + rev + "'";
            pendingCommit.start(inputs.getMergeOid(), mergeMsg);

            MergeResolve mergeResolve = new MergeResolve(inputs, rev);
            mergeResolve.onProgress(System.out::println);
            mergeResolve.execute();

            if (repo.getIndex().isConflicted()) {
                System.err.println("error: merge conflicts detected.");
                exitCode = 1;
                return;
            }

            String newCommitOid = WriteCommit.writeCommit(Arrays.asList(inputs.getHeadOid(), inputs.getMergeOid()), mergeMsg);
            pendingCommit.clear();
            System.out.println("Merge made. New commit: " + ObjectDatabase.shortOid(newCommitOid));
        } catch (RevisionParseException e) {
            log.warn("merge parse revision failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        } catch (IOException e) {
            log.error("merge failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }

    private void handleContinue(PendingCommit pendingCommit) throws IOException {
        if (!pendingCommit.inProgress()) {
            System.err.println("error: no merge in progress.");
            exitCode = 1;
            return;
        }
        repo.getIndex().load();
        if (repo.getIndex().isConflicted()) {
            System.err.println("error: cannot continue, unresolved conflicts remain.");
            exitCode = 1;
            return;
        }
        String headOid = repo.getRefs().readHead();
        String mergeHeadOid = pendingCommit.readMergeHead();
        String mergeMessage = pendingCommit.readMergeMessage();
        String newCommitOid = WriteCommit.writeCommit(Arrays.asList(headOid, mergeHeadOid), mergeMessage);
        pendingCommit.clear();
        System.out.println("Merge made. New commit: " + ObjectDatabase.shortOid(newCommitOid));
    }
}
