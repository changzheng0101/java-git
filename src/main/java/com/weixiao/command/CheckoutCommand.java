package com.weixiao.command;

import com.weixiao.repo.Migration;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.revision.Revision;
import com.weixiao.revision.RevisionParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.util.LinkedHashMap;

/**
 * jit checkout - 将工作区和暂存区切换到指定 commit（或分支指向的 commit）。
 * 通过 Migration 先更新 workspace 再更新 index，最后更新 HEAD。
 */
@Command(name = "checkout", mixinStandardHelpOptions = true,
        description = "切换到指定 commit 或分支（更新工作区与暂存区）")
public class CheckoutCommand extends BaseCommand {

    private static final Logger log = LoggerFactory.getLogger(CheckoutCommand.class);

    @SuppressWarnings("unused")
    @Parameters(index = "0", arity = "1", paramLabel = "REF", description = "分支名或 commit id")
    private String ref;

    @Override
    protected void initParams() {
        params = new LinkedHashMap<>();
        if (ref != null) {
            params.put("ref", ref);
        }
    }

    @Override
    protected void doRun() {
        String refVal = get("ref");
        log.debug("checkout start path={} ref={}", getStartPath(), refVal);
        try {
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

}
