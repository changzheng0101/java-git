package com.weixiao.command;

import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Refs;
import com.weixiao.repo.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * jit branch：
 * - 无参数：列出所有分支，按字母排序，当前分支前加 *；
 * - 有 NAME 参数：在当前 HEAD 上新建 refs/heads/&lt;name&gt;（分支名校验与 Git check-ref-format 一致）；
 * - --delete/-d/--force/-D：删除分支（目前仅在 force=true 时真正删除）。
 */
@Command(name = "branch", mixinStandardHelpOptions = true, description = "列出或创建分支")
public class BranchCommand extends BaseCommand {

    private static final Logger log = LoggerFactory.getLogger(BranchCommand.class);

    private static final String BRANCH_NAMES_SEP = "\n";

    @Option(names = {"-v", "--verbose"}, description = "显示每个分支指向的提交和标题行")
    private boolean verbose;

    @Option(names = {"-d", "--delete"}, description = "删除分支（安全删除，当前实现需配合 --force 使用）")
    private boolean delete;

    @Option(names = {"--force", "-D"}, description = "强制删除分支")
    private boolean force;

    @Parameters(index = "0", arity = "0..*", paramLabel = "NAME", description = "分支名称（省略则列出分支）")
    private List<String> branchNames;

    @Override
    protected void initParams() {
        params = new LinkedHashMap<>();
        if (verbose) {
            params.put("verbose", "");
        }
        if (delete) {
            params.put("delete", "");
        }
        if (force) {
            params.put("force", "");
        }
        if (branchNames != null && !branchNames.isEmpty()) {
            params.put("branchNames", String.join(BRANCH_NAMES_SEP, branchNames));
        }
    }

    @Override
    protected void doRun() {
        log.debug("branch start path={} names={}", getStartPath(), get("branchNames"));
        try {
            boolean deleteMode = isSet("delete") || isSet("force");
            String branchNamesStr = get("branchNames");
            List<String> names = branchNamesStr == null || branchNamesStr.isEmpty()
                    ? Collections.emptyList()
                    : Arrays.asList(branchNamesStr.split(BRANCH_NAMES_SEP));

            if (deleteMode) {
                if (names.isEmpty()) {
                    System.err.println("fatal: branch name required");
                    exitCode = 1;
                    return;
                }
                deleteBranches(repo, names, isSet("force"));
                return;
            }

            if (names.isEmpty()) {
                listBranches(repo);
                return;
            }

            if (names.size() > 1) {
                System.err.println("fatal: too many branch names for creation");
                exitCode = 1;
                return;
            }

            String branchName = names.get(0);
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
            String oid = repo.getRefs().readHead();
            if (oid == null || oid.isEmpty()) {
                System.err.println("fatal: Not a valid object name: 'HEAD'.");
                exitCode = 1;
                return;
            }
            repo.getRefs().createBranch(branchName, oid);
            log.info("branch created name={} oid={}", branchName, oid);
        } catch (IOException e) {
            log.error("branch failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }

    /**
     * 删除一个或多个分支：当前实现只有在 force=true 时才真正删除。
     */
    private void deleteBranches(Repository repo, List<String> names, boolean forceDelete) throws IOException {
        Refs refs = repo.getRefs();
        for (String name : names) {
            if (!forceDelete) {
                System.err.println("error: branch '" + name + "' not deleted; use --force or -D to delete it");
                exitCode = 1;
                continue;
            }
            try {
                String oid = refs.deleteBranch(name);
                String abbrev = ObjectDatabase.shortOid(oid);
                System.out.println("Deleted branch " + name + " (" + abbrev + ")");
            } catch (IOException e) {
                System.err.println("error: " + e.getMessage());
                exitCode = 1;
            }
        }
    }

    /**
     * 列出所有分支，按名字排序；当前 HEAD 所在分支前加 *。
     * 若 --verbose，则附加输出 abbrev commitId 和 message 首行。
     */
    private void listBranches(Repository repo) throws IOException {
        Map<String, String> namesToOid = repo.getRefs().getBranchNamesToOid();
        List<String> names = new ArrayList<>(namesToOid.keySet());
        Collections.sort(names);

        String currentBranch = repo.getRefs().getCurrentBranchName();

        for (String name : names) {
            boolean current = name.equals(currentBranch);
            String prefix = current ? "*" : " ";

            if (!isSet("verbose")) {
                System.out.println(prefix + " " + name);
            } else {
                String oid = namesToOid.get(name);
                if (oid == null || oid.isEmpty()) {
                    System.out.println(prefix + " " + name);
                    continue;
                }
                String abbrev = ObjectDatabase.shortOid(oid);
                String subject = repo.getCommitShortMessage(oid);
                // 与 git 类似：分支名、缩写 oid、标题行
                System.out.println(prefix + " " + name + " " + abbrev + " " + subject);
            }
        }
    }

}
