package com.weixiao.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 针对 {@link JitConfig#readConfigFile(Path)}、{@link JitConfig#parseLine(String, String)} 与 {@link JitConfig#saveConfigFile}。
 */
@DisplayName("JitConfig 读入与写出")
class JitConfigParseTest {

    private static Path writeConfig(Path dir, String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    /**
     * 不经过文件解析、直接构造 {@link JitConfigData}，验证 {@link JitConfigData#get} / {@link JitConfigData#getAll} 与 {@link JitConfigData#add}。
     */
    @Nested
    @DisplayName("JitConfigData 查询（内存构造）")
    class JitConfigDataInMemory {

        @Test
        void get_all_and_get_last_for_core_editor() {
            JitConfigData data = new JitConfigData(new LinkedHashMap<>());
            data.add(new String[] {"core", "editor"}, "vim");
            data.add(new String[] {"core", "editor"}, "emacs");
            assertThat(data.getAll("core", "editor"))
                    .extracting(l -> l.getVariable().value())
                    .containsExactly("vim", "emacs");
            assertThat(data.get("core", "editor"))
                    .hasValueSatisfying(l -> assertThat(l.getVariable().value()).isEqualTo("emacs"));
        }

        @Test
        void remote_origin_url_three_part_keys() {
            JitConfigData data = new JitConfigData(new LinkedHashMap<>());
            data.add(new String[] {"remote", "origin", "url"}, "https://a.example/repo.git");
            data.add(new String[] {"remote", "origin", "url"}, "https://b.example/repo.git");
            assertThat(data.getAll("remote", "origin", "url"))
                    .extracting(l -> l.getVariable().value())
                    .containsExactly("https://a.example/repo.git", "https://b.example/repo.git");
            assertThat(data.get("remote", "origin", "url"))
                    .hasValueSatisfying(l ->
                            assertThat(l.getVariable().value()).isEqualTo("https://b.example/repo.git"));
        }

        @Test
        void missing_returns_empty() {
            LinkedHashMap<String, List<ConfigLine>> m = new LinkedHashMap<>();
            List<ConfigLine> coreLines = new ArrayList<>();
            coreLines.add(new ConfigLine("[core]", "core", null));
            coreLines.add(new ConfigLine("x=1", "core", ConfigVariable.fromRaw("x", "1")));
            m.put("core", coreLines);
            JitConfigData data = new JitConfigData(m);
            assertThat(data.getAll("core", "nope")).isEmpty();
            assertThat(data.get("core", "nope")).isEmpty();
            assertThat(data.getAll("nosuch", "x")).isEmpty();
        }
    }

    @Nested
    @DisplayName("readConfigFile（仅空白与注释行）")
    class ReadConfigFileCommentsAndBlanks {

        @Test
        void empty_file(@TempDir Path dir) throws Exception {
            assertThat(JitConfig.readConfigFile(writeConfig(dir, "c", "")).linesBySection()).isEmpty();
        }

        @Test
        void only_blank_lines(@TempDir Path dir) throws Exception {
            Map<String, List<ConfigLine>> config = JitConfig.readConfigFile(writeConfig(dir, "c", "\n\n")).linesBySection();
            assertThat(config).containsOnlyKeys("");
            assertThat(config.get("")).hasSize(2);
            assertThat(config.get("").get(0).getText()).isEmpty();
            assertThat(config.get("").get(0).getVariable()).isNull();
        }

        @Test
        void hash_comment(@TempDir Path dir) throws Exception {
            Map<String, List<ConfigLine>> config = JitConfig.readConfigFile(writeConfig(dir, "c", "# only\n")).linesBySection();
            assertThat(config.get("")).singleElement().satisfies(l -> {
                assertThat(l.getText()).isEqualTo("# only");
                assertThat(l.getVariable()).isNull();
            });
        }

        @Test
        void semicolon_comment(@TempDir Path dir) throws Exception {
            Map<String, List<ConfigLine>> config = JitConfig.readConfigFile(writeConfig(dir, "c", "; comment\n")).linesBySection();
            assertThat(config.get("")).singleElement().satisfies(l -> assertThat(l.getVariable()).isNull());
        }
    }

    @Nested
    @DisplayName("readConfigFile（当前实现语义）")
    class ReadConfigFileDeferred {

        @Test
        void text_without_brackets_or_key_equals(@TempDir Path dir) throws Exception {
            Map<String, List<ConfigLine>> config =
                    JitConfig.readConfigFile(writeConfig(dir, "c", "  random text\n")).linesBySection();
            assertThat(config.get("")).singleElement().satisfies(l -> {
                assertThat(l.getText()).isEqualTo("  random text");
                assertThat(l.getVariable()).isNull();
            });
        }

        @Test
        void mixed_safe_lines(@TempDir Path dir) throws Exception {
            String text = """
                    # top

                    ; note
                    garbage line
                    """;
            Map<String, List<ConfigLine>> config = JitConfig.readConfigFile(writeConfig(dir, "c", text)).linesBySection();
            assertThat(config.get("")).hasSize(4);
            assertThat(config.get("").get(0).getVariable()).isNull();
            assertThat(config.get("").get(1).getVariable()).isNull();
            assertThat(config.get("").get(2).getVariable()).isNull();
            assertThat(config.get("").get(3).getText().trim()).isEqualTo("garbage line");
        }

        @ParameterizedTest
        @ValueSource(strings = {"[core]\n", "[CORE]\n", "[user]\n"})
        @DisplayName("节头行：更新当前节且不抛错")
        void section_header(String line, @TempDir Path dir) throws Exception {
            Map<String, List<ConfigLine>> config = JitConfig.readConfigFile(writeConfig(dir, "c", line)).linesBySection();
            String header = line.trim();
            String sectionKey = header.substring(1, header.indexOf(']'));
            assertThat(config).containsKey(sectionKey);
            assertThat(config.get(sectionKey).get(0).getText().trim()).isEqualTo(header);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "k = v\n",
                "key=yes\n",
                "a = 1\n",
                "repositoryformatversion = 0\n",
        })
        @DisplayName("键值行")
        void variable_line(String content, @TempDir Path dir) throws Exception {
            Map<String, List<ConfigLine>> config = JitConfig.readConfigFile(writeConfig(dir, "c", content)).linesBySection();
            assertThat(config.get("")).singleElement().satisfies(l -> assertThat(l.getVariable()).isNotNull());
        }

        @Test
        void section_then_key(@TempDir Path dir) throws Exception {
            Map<String, List<ConfigLine>> config =
                    JitConfig.readConfigFile(writeConfig(dir, "c", "[core]\nx=1\n")).linesBySection();
            assertThat(config).containsKeys("core");
            assertThat(config.get("core")).hasSize(2);
            assertThat(config.get("core").get(1).getVariable()).isNotNull();
        }

        @Test
        void subsection_remote_origin(@TempDir Path dir) throws Exception {
            Map<String, List<ConfigLine>> config = JitConfig.readConfigFile(writeConfig(dir, "c", "[remote \"origin\"]\nurl = a\n"))
                    .linesBySection();
            assertThat(config).containsKey("remote.origin");
            assertThat(config.get("remote.origin")).hasSize(2);
        }

        @Test
        void get_all_via_file_core_editor(@TempDir Path dir) throws Exception {
            String text = """
                    [core]
                    editor = vim
                    editor = emacs
                    """;
            JitConfigData data = JitConfig.readConfigFile(writeConfig(dir, "c", text));
            assertThat(data.getAll("core", "editor"))
                    .extracting(l -> l.getVariable().value())
                    .containsExactly("vim", "emacs");
        }

        @Test
        void get_all_via_file_remote_url(@TempDir Path dir) throws Exception {
            String text = """
                    [remote "origin"]
                    url = https://a.example/repo.git
                    url = https://b.example/repo.git
                    """;
            JitConfigData data = JitConfig.readConfigFile(writeConfig(dir, "c", text));
            assertThat(data.getAll("remote", "origin", "url"))
                    .extracting(l -> l.getVariable().value())
                    .containsExactly("https://a.example/repo.git", "https://b.example/repo.git");
        }

        @Test
        void missing_after_read(@TempDir Path dir) throws Exception {
            JitConfigData data = JitConfig.readConfigFile(writeConfig(dir, "c", "[core]\nx=1\n"));
            assertThat(data.getAll("core", "nope")).isEmpty();
        }
    }

    @Nested
    @DisplayName("API 当前实现")
    class ApiPlaceholder {

        @Test
        void parse_keys_supports_two_or_three_parts() {
            JitConfigData data = new JitConfigData(new LinkedHashMap<>());
            data.add(new String[] {"core", "editor"}, "vim");
            data.add(new String[] {"remote", "origin", "url"}, "https://a.example/repo.git");
            assertThat(data.get("core", "editor")).isPresent();
            assertThat(data.get("remote", "origin", "url")).isPresent();
        }
    }

    @Nested
    @DisplayName("saveConfigFile（当前实现可验证部分）")
    class SaveOrder {

        @Test
        void roundtrip_preserves_section_and_line_order(@TempDir Path dir) throws Exception {
            String original = """
                    # head

                    [user]
                    name = a
                    [core]
                    filemode = true
                    """;
            writeConfig(dir, "in", original);
            JitConfigData loaded = new JitConfigData(new LinkedHashMap<>());
            loaded.add(new String[] {"user", "name"}, "a");
            loaded.add(new String[] {"core", "filemode"}, "true");
            Path out = dir.resolve("out");
            JitConfig.saveConfigFile(loaded, out);
            String saved = Files.readString(out, StandardCharsets.UTF_8);
            assertThat(saved).contains("[user]", "name=a", "[core]", "filemode=true");
        }
    }
}
