package com.weixiao.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jit 配置文件：支持 local / global / system 三层，与 Git 行为对齐。
 * <p>
 * 解析结果见 {@link JitConfigData}；节名为空串表示文件开头、尚未出现任何 {@code [section]} 的行。
 */
public final class JitConfig {

    /**
     * 用户级配置默认路径（{@code ~/.gitconfig}，已规范化）。
     */
    public static final Path GLOBAL_CONFIG = Path.of(System.getProperty("user.home")).resolve(".gitconfig").normalize();

    /**
     * 系统级配置默认路径。
     */
    public static final Path SYSTEM_CONFIG = Path.of("/etc/gitconfig");

    // ^\[(\w*)\s*(\"(\w*)\")?]
    private static final Pattern SECTION_PAT = Pattern.compile("^\\[(\\w*)\\s*(\\\"(\\w*)\\\")?]");

    // ^\s*(\w*)\s*=\s*([\w|\.]*)
    private static final Pattern VAR_PAT = Pattern.compile("^\\s*(\\w*)\\s*=\\s*(\\S*)");
    /**
     * 空行，或行首空白后以 # / ; 开头的整行注释。
     */
    private static final Pattern BLANK_OR_COMMENT = Pattern.compile("^\\s*([#;]|)");

    private final Map<GitConfigScope, Path> configPaths;
    private final EnumMap<GitConfigScope, JitConfigData> dataByScope = new EnumMap<>(GitConfigScope.class);

    /**
     * 合并查询顺序：与 Git 一致，后写层覆盖先写层；{@link #getAll(String...)} 展平后最后一个即「有效值」。
     */
    private static final GitConfigScope[] EFFECTIVE_VALUE_ORDER = {
            GitConfigScope.SYSTEM,
            GitConfigScope.GLOBAL,
            GitConfigScope.LOCAL,
    };

    /**
     * @param localConfigPath 本仓库 local 配置路径，通常为 {@code .git/config}
     */
    public JitConfig(Path localConfigPath) {
        EnumMap<GitConfigScope, Path> paths = new EnumMap<>(GitConfigScope.class);
        paths.put(GitConfigScope.LOCAL, localConfigPath);
        paths.put(GitConfigScope.GLOBAL, GLOBAL_CONFIG);
        paths.put(GitConfigScope.SYSTEM, SYSTEM_CONFIG);
        this.configPaths = Collections.unmodifiableMap(paths);
    }

    public Path getPath(GitConfigScope scope) {
        return configPaths.get(scope);
    }

    /**
     * 加载对应scope的数据。
     */
    public JitConfigData load(GitConfigScope scope) {
        if (dataByScope.containsKey(scope)) {
            return dataByScope.get(scope);
        }

        Path path = configPaths.get(scope);
        JitConfigData loaded = loadFromPath(path);
        dataByScope.put(scope, loaded);
        return loaded;
    }

    /**
     * 获取key对应的configFile  local优先生效
     * 返回key对应的value
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String... keys) {
        List<ConfigLine> all = getAll(keys);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of((T) all.get(all.size() - 1).getVariable().value());
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(GitConfigScope scope, String... keys) {
        JitConfigData jitConfigData = load(scope);
        return jitConfigData.get(keys).map(configLine -> (T) configLine.getVariable().value());
    }

    /**
     * 获取所有配置，三个文件都会读取
     */
    public List<ConfigLine> getAll(String... keys) {
        List<ConfigLine> merged = new ArrayList<>();
        for (GitConfigScope scope : EFFECTIVE_VALUE_ORDER) {
            merged.addAll(load(scope).getAll(keys));
        }
        return List.copyOf(merged);
    }

    private static JitConfigData loadFromPath(Path path) {
        if (Files.exists(path)) {
            return readConfigFile(path);
        }
        return new JitConfigData(new LinkedHashMap<>());
    }

    /**
     * @param configFilePath config file path
     * @return section -> List of configLine
     */
    public static JitConfigData readConfigFile(Path configFilePath) {
        LinkedHashMap<String, List<ConfigLine>> config = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(configFilePath)) {
            String line;
            String currentSection = "";
            while ((line = reader.readLine()) != null) {
                ConfigLine configLine = parseLine(line, currentSection);
                currentSection = configLine.getSection();
                config.computeIfAbsent(configLine.getSection(), k -> new ArrayList<>()).add(configLine);
            }
        } catch (IOException e) {
            throw new RuntimeException("error reading config file", e);
        }
        return new JitConfigData(config);
    }

    /**
     * 解析一行配置。
     */
    public static ConfigLine parseLine(String rawLine, String section) {
        ConfigVariable variable = null;

        Matcher sectionMatcher = SECTION_PAT.matcher(rawLine);
        if (sectionMatcher.find()) {
            if (sectionMatcher.group(3) != null) {
                section = sectionMatcher.group(1) + "." + sectionMatcher.group(3);
            } else {
                section = sectionMatcher.group(1);
            }
            return new ConfigLine(rawLine, section, variable);
        }
        Matcher varMatcher = VAR_PAT.matcher(rawLine);
        if (varMatcher.find()) {
            String key = varMatcher.group(1);
            String value = varMatcher.group(2);
            variable = ConfigVariable.fromRaw(key, value);
            return new ConfigLine(rawLine, section, variable);
        }
        Matcher commentMatcher = BLANK_OR_COMMENT.matcher(rawLine);
        if (commentMatcher.find()) {
            return new ConfigLine(rawLine, section, null);
        }

        throw new RuntimeException("bad config line in file");
    }

    public void saveConfigFile(GitConfigScope scope) {
        Path configFilePath = configPaths.get(scope);
        JitConfigData data = dataByScope.get(scope);
        saveConfigFile(data, configFilePath);
    }

    /**
     * 按 {@link Map} 的迭代顺序写出各节下的行；若需与读入顺序一致，应使用 {@link JitConfig#readConfigFile} 得到的数据或 {@link LinkedHashMap}。
     */
    public static void saveConfigFile(JitConfigData data, Path configFilePath) {
        Map<String, List<ConfigLine>> configLines = data.linesBySection();
        try (BufferedWriter writer = Files.newBufferedWriter(configFilePath)) {
            for (Map.Entry<String, List<ConfigLine>> entry : configLines.entrySet()) {
                for (ConfigLine configLine : entry.getValue()) {
                    writer.write(configLine.getText() + "\n");
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("error saving config file", e);
        }
    }


}
