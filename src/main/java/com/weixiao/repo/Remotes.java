package com.weixiao.repo;

import com.weixiao.config.ConfigLine;
import com.weixiao.config.ConfigVariable;
import com.weixiao.config.GitConfigScope;
import com.weixiao.config.JitConfig;
import com.weixiao.config.JitConfigData;
import lombok.AllArgsConstructor;

import java.io.IOException;
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


    public List<String> listingLines(boolean verbose) {
        List<String> lines = new ArrayList<>();
        for (String name : listRemoteNames()) {
            if (!verbose) {
                lines.add(name);
            } else {
                String fetchPart = getUrl(name).orElse("");
                String pushPart = getPushUrl(name).orElse("");
                lines.add(name + "\t" + fetchPart + " (fetch)");
                lines.add(name + "\t" + pushPart + " (push)");
            }
        }
        return lines;
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
