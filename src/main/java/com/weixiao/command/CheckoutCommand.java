package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.repo.Migration;
import com.weixiao.repo.Repository;
import com.weixiao.revision.Revision;
import com.weixiao.revision.RevisionParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Path;

/**
 * jit checkout - 将工作区和暂存区切换到指定 commit（或分支指向的 commit）。
 * 通过 Migration 先更新 workspace 再更新 index，最后更新 HEAD。
 */
@Command(name = "checkout", mixinStandardHelpOptions = true,
        description = "切换到指定 commit 或分支（更新工作区与暂存区）")
public class CheckoutCommand implements Runnable, IExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CheckoutCommand.class);

    @ParentCommand
    private Jit jit;

    @Parameters(index = "0", arity = "1", paramLabel = "REF", description = "分支名或 commit id")
    private String ref;

    private int exitCode = 0;

    @Override
    public void run() {
        exitCode = 0;
        Path start = jit.getStartPath();
        log.debug("checkout start path={} ref={}", start, ref);

        Repository repo = Repository.find(start);
        if (repo == null) {
            log.debug("no repo found from {}", start);
            System.err.println("fatal: not a jit repository (or any of the parent directories): .git");
            exitCode = 1;
            return;
        }

        try {
            Revision rev = Revision.parse(ref);
            String targetCommitOid = rev.getCommitId(repo);
            String headOid = repo.getRefs().readHead();
            if (targetCommitOid.equals(headOid)) {
                log.info("already at {}", targetCommitOid);
                return;
            }

            Migration migration = new Migration(headOid, targetCommitOid);
            migration.validate();
            migration.applyChanges();
            log.info("checkout done ref={} oid={}", ref, targetCommitOid);
        } catch (RevisionParseException e) {
            log.warn("checkout parse failed ref={}", ref, e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        } catch (IOException e) {
            log.error("checkout failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
