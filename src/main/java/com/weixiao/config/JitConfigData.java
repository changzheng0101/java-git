package com.weixiao.config;

import com.google.common.base.Preconditions;

import java.util.*;

/**
 * 已解析的 Jit 配置文件：按节名索引到行列表，并提供按 Git 风格路径的查询。
 * <p>
 * 节名规则：无子节为 {@code core}；有子节为 {@code remote.origin}（对应文件中的 {@code [remote "origin"]}）。
 * <p>
 * {@link #get} / {@link #getAll} 的路径参数：最后一项为变量名，前面各项组成节名（点分）。
 * 例如 {@code get("core", "editor")}、{@code get("remote", "origin", "url")}。
 */
public final class JitConfigData {

    private final LinkedHashMap<String, List<ConfigLine>> linesBySection;

    JitConfigData(LinkedHashMap<String, List<ConfigLine>> linesBySection) {
        this.linesBySection = Preconditions.checkNotNull(linesBySection);
    }

    /**
     * 只读视图；迭代顺序与读入/写入顺序一致（底层为 {@link LinkedHashMap}）。
     */
    public Map<String, List<ConfigLine>> linesBySection() {
        return Collections.unmodifiableMap(linesBySection);
    }

    /**
     * 同 {@link #getAll}，但只返回<strong>最后一个</strong>值（Git 中同一键可出现多次，以后者为准的常见语义）。
     */
    public Optional<ConfigLine> get(String... pathParts) {
        List<ConfigLine> all = getAll(pathParts);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(all.get(all.size() - 1));
    }

    /**
     * 按路径取该变量在对应节下出现的全部解析值，顺序与文件中出现的顺序一致。
     */
    public List<ConfigLine> getAll(String... keys) {
        Preconditions.checkArgument(keys != null && keys.length >= 2, "至少为 [section, varName]");
        String[] parseRes = parseKeys(keys);
        String section = parseRes[0];
        String varName = parseRes[1];

        return findLines(section, varName);
    }

    /**
     * 添加值，如果之前已经key已经有对应的值，则追加一条
     */
    public void add(String[] keys, String value) {
        String[] parseRes = parseKeys(keys);
        String section = parseRes[0];
        String varName = parseRes[1];

        if (!linesBySection.containsKey(section)) {
            linesBySection.put(section, new ArrayList<>());
            linesBySection.get(section).add(new ConfigLine("[" + section + "]", section, null));
        }
        linesBySection.get(section).add(
                new ConfigLine(varName + "=" + value, section, new ConfigVariable(varName, value))
        );
    }

    /**
     * 只有一个值的时候，进行设置
     *
     * @param keys  section.[].varName
     * @param value varValue
     */
    public void set(String[] keys, String value) {
        String[] parseRes = parseKeys(keys);
        String section = parseRes[0];
        String varName = parseRes[1];

        List<ConfigLine> all = getAll(section, varName);
        if (all.isEmpty()) {
            add(keys, value);
        } else if (all.size() == 1) {
            all.get(0).setVariable(new ConfigVariable(varName, value));
        } else {
            throw new RuntimeException("cannot overwrite multiple values with a single value");
        }
    }


    /**
     * 先移除所有的key  之后再插入一条
     */
    public void replaceAll(String[] keys, String value) {
        String[] parseRes = parseKeys(keys);
        String section = parseRes[0];
        String varName = parseRes[1];

        removeAllKey(section, varName);
        add(keys, value);
    }

    /**
     * 移除某个key对应的所有行
     *
     */
    public void unsetAll(String[] keys) {
        String[] parseRes = parseKeys(keys);
        String section = parseRes[0];
        String varName = parseRes[1];

        removeAllKey(section, varName);
        removeSection(section);
    }

    /**
     * 移除某个key对应的行,对应的key只能有一行
     */
    public void unset(String[] keys) {
        String[] parseRes = parseKeys(keys);
        String section = parseRes[0];
        String varName = parseRes[1];

        List<ConfigLine> all = getAll(keys);
        if (all.size() != 1) {
            throw new RuntimeException("unset must have exactly one line");
        }

        removeAllKey(section, varName);
        removeSection(section);
    }


    private void removeSection(String section) {
        List<ConfigLine> configLines = linesBySection.getOrDefault(section, new ArrayList<>());
        if (configLines.isEmpty()) {
            linesBySection.remove(section);
        }
    }

    private void removeAllKey(String section, String varName) {
        List<ConfigLine> all = getAll(section, varName);
        if (!all.isEmpty()) {
            linesBySection().get(section).removeAll(all);
        }
    }

    private List<ConfigLine> findLines(String section, String varName) {
        List<ConfigLine> lines = linesBySection.getOrDefault(section, new ArrayList<>());
        List<ConfigLine> res = new ArrayList<>();
        for (ConfigLine line : lines) {
            ConfigVariable v = line.getVariable();
            if (v != null && v.key().equalsIgnoreCase(varName)) {
                res.add(line);
            }
        }
        return res;
    }

    private String[] parseKeys(String[] keys) {
        String[] res = new String[2];
        if (keys.length == 2) {
            res[0] = keys[0];
        } else if (keys.length == 3) {
            res[0] = keys[0] + "." + keys[1];
        } else {
            throw new RuntimeException("error when parse keys");
        }
        res[1] = keys[keys.length - 1];
        return res;
    }

}
