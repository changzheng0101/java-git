package com.weixiao.command;

import com.weixiao.repo.Migration;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Refs;
import com.weixiao.revision.Revision;
import com.weixiao.revision.RevisionParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.util.Objects;

/**
 * jit checkout - 将工作区和暂存区切换到指定 commit（或分支指向的 commit）。
 * 通过 Migration 先更新 workspace 再更新 index，最后更新 HEAD。
 */
@Command(name = "checkout", mixinStandardHelpOptions = true,
        description = "切换到指定 commit 或分支（更新工作区与暂存区）")
public class CheckoutCommand extends BaseCommand {

    private static final Logger log = LoggerFactory.getLogger(CheckoutCommand.class);

    @SuppressWarnings("unused")
    @Option(names = {"-b"}, paramLabel = "BRANCH", description = "创建并切换到新分支")
    private String newBranch;

    @SuppressWarnings("unused")
    @Parameters(index = "0", arity = "0..1", paramLabel = "REF", description = "分支名或 commit id")
    private String ref;

    @Override
    protected void doRun() {
        String refVal = ref;
        log.debug("checkout start path={} ref={} newBranch={}", getStartPath(), refVal, newBranch);
        try {
            if (newBranch != null) {
                checkoutNewBranch();
                return;
            }
            if (refVal == null || refVal.isBlank()) {
                System.err.println("fatal: checkout target is required.");
                exitCode = 1;
                return;
            }
            String headOid = repo.getRefs().readHead();
            boolean isBranch = repo.getRefs().branchExists(refVal);
            String targetCommitOid = Revision.parse(refVal).getCommitId(repo);

            if (isBranch && targetCommitOid.equals(headOid)) {
                String currentBranch = repo.getRefs().getCurrentBranchName();
                if (refVal.equals(currentBranch)) {
                    System.out.println("Already on '" + refVal + "'");
                    log.info("already on branch {}", refVal);
                    return;
                }
            }

            if (!targetCommitOid.equals(headOid)) {
                Migration migration = new Migration(headOid, targetCommitOid);
                migration.validate();
                migration.applyChanges();
            }

            repo.getRefs().updateHead(refVal, targetCommitOid);

            if (isBranch) {
                System.out.println("Switched to branch '" + refVal + "'");
            } else {
                String subject = repo.getCommitShortMessage(targetCommitOid);
                String abbrev = ObjectDatabase.shortOid(targetCommitOid);
                System.out.println("HEAD is now at " + abbrev + " " + subject);
            }
            log.info("checkout done ref={} oid={}", refVal, targetCommitOid);
        } catch (RevisionParseException e) {
            log.warn("checkout parse failed ref={}", refVal, e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        } catch (IOException e) {
            log.error("checkout failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }

    private void checkoutNewBranch() throws IOException {
        String branchName = newBranch;
        String invalid = Refs.validateBranchName(branchName);
        if (invalid != null) {
            System.err.println("fatal: '" + branchName + "' is not a valid branch name: " + invalid);
            exitCode = 1;
            return;
        }
        if (repo.getRefs().branchExists(branchName)) {
            System.err.println("fatal: A branch named '" + branchName + "' already exists.");
            exitCode = 1;
            return;
        }

        String headOid = repo.getRefs().readHead();
        String targetCommitOid = resolveCheckoutStartPoint(headOid);
        if (targetCommitOid == null) {
            return;
        }

        if (!Objects.equals(targetCommitOid, headOid)) {
            Migration migration = new Migration(headOid, targetCommitOid);
            migration.validate();
            migration.applyChanges();
        }

        repo.getRefs().createBranch(branchName, targetCommitOid);
        repo.getRefs().writeHeadToBranch(branchName);
        System.out.println("Switched to a new branch '" + branchName + "'");
    }

    private String resolveCheckoutStartPoint(String headOid) throws IOException {
        if (ref == null || ref.isBlank()) {
            if (headOid == null || headOid.isBlank()) {
                System.err.println("fatal: Not a valid object name: 'HEAD'.");
                exitCode = 1;
                return null;
            }
            return headOid;
        }
        return Revision.parse(ref).getCommitId(repo);
    }

}
