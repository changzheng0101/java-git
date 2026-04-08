package com.weixiao.repo;

import com.weixiao.config.ConfigLine;
import com.weixiao.config.ConfigVariable;
import com.weixiao.config.GitConfigScope;
import com.weixiao.config.JitConfig;
import com.weixiao.config.JitConfigData;
import lombok.AllArgsConstructor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 读取与维护 {@code .git/config} 中的 {@code [remote ...]} 节：URL、fetch refspec 等。
 */
@AllArgsConstructor
public final class Remotes {

    private static final String REMOTE_PREFIX = "remote.";


    /**
     * 按配置文件中出现的顺序返回 remote 名（{@code remote.<name>} 的 {@code name}）。
     */
    public List<String> listRemoteNames() {
        JitConfigData data = Repository.INSTANCE.getJitConfig().load(GitConfigScope.LOCAL);
        List<String> names = new ArrayList<>();
        for (String section : data.linesBySection().keySet()) {
            if (section.startsWith(REMOTE_PREFIX)) {
                String remoteName = section.substring(REMOTE_PREFIX.length());
                if (!remoteName.isEmpty()) {
                    names.add(remoteName);
                }
            }
        }
        return names;
    }

    /**
     * local 配置中该 remote 下全部 {@code fetch} 行解析为 {@link Refspec}。
     */
    public List<Refspec> getFetchRefspecs(String remoteName) {
        JitConfigData data = Repository.INSTANCE.getJitConfig().load(GitConfigScope.LOCAL);
        List<ConfigLine> lines = data.getAll("remote", remoteName, "fetch");
        List<Refspec> out = new ArrayList<>();
        for (ConfigLine line : lines) {
            ConfigVariable v = line.getVariable();
            if (v != null && v.value() != null) {
                out.add(Refspec.parse(String.valueOf(v.value())));
            }
        }
        return out;
    }

    public Optional<String> getUrl(String remoteName) {
        return Repository.INSTANCE.getJitConfig().get(GitConfigScope.LOCAL, REMOTE_PREFIX + remoteName, "url");
    }

    /**
     * 若未设置 {@code pushurl}，则与 {@link #getUrl(String)} 相同（与 Git 一致）。
     */
    public Optional<String> getPushUrl(String remoteName) {
        Optional<String> push = Repository.INSTANCE.getJitConfig().get(GitConfigScope.LOCAL, REMOTE_PREFIX + remoteName, "pushurl");
        if (push.isPresent() && !push.get().isEmpty()) {
            return push;
        }
        return getUrl(remoteName);
    }


    private static String stringifyValue(Object v) {
        if (v == null) {
            return "";
        }
        return String.valueOf(v).trim();
    }

    /**
     * 列出 remote；{@code verbose} 为真时每项输出两行 {@code (fetch)} / {@code (push)}，与 {@code git remote -v} 一致。
     */
    public void printListing(boolean verbose) {
        for (String name : listRemoteNames()) {
            if (!verbose) {
                System.out.println(name);
                continue;
            }
            String fetchPart = getUrl(name).orElse("");
            String pushPart = getPushUrl(name).orElse("");
            System.out.println(name + "\t" + fetchPart + " (fetch)");
            System.out.println(name + "\t" + pushPart + " (push)");
        }
    }

    /**
     * 删除整个 {@code [remote "name"]} 节并写回配置。
     */
    public void remove(String name) throws IOException {
        if (name == null || name.isEmpty()) {
            throw new IOException("remote name required");
        }
        JitConfig cfg = Repository.INSTANCE.getJitConfig();
        if (cfg == null) {
            throw new IOException("repository not initialized");
        }
        JitConfigData data = cfg.load(GitConfigScope.LOCAL);
        String sectionKey = REMOTE_PREFIX + name;
        if (!data.linesBySection().containsKey(sectionKey)) {
            throw new IOException("No such remote: '" + name + "'");
        }
        data.removeEntireSection(sectionKey);
        cfg.saveConfigFile(GitConfigScope.LOCAL);
    }
}
