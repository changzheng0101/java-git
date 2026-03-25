package com.weixiao.revision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings("DataFlowIssue")
@DisplayName("Revision 解析测试")
class RevisionTest {

    private static Revision.Ref refOf(Revision r) {
        return (Revision.Ref) r;
    }

    private static Revision.Parent parentOf(Revision r) {
        return (Revision.Parent) r;
    }

    private static Revision.Ancestor ancestorOf(Revision r) {
        return (Revision.Ancestor) r;
    }

    @Test
    @DisplayName("@ 解析为 Ref(HEAD)")
    void parse_at_returnsRefHead() {
        Revision r = Revision.parse("@");
        assertThat(r.getKind()).isEqualTo(Revision.Kind.REF);
        assertThat(refOf(r).name()).isEqualTo("HEAD");
        assertThat(refOf(Revision.parse("  @  ")).name()).isEqualTo("HEAD");
    }

    @Test
    @DisplayName("refname 解析为 Ref")
    void parse_refname_returnsRef() {
        Revision r = Revision.parse("HEAD");
        assertThat(r.getKind()).isEqualTo(Revision.Kind.REF);
        assertThat(refOf(r).name()).isEqualTo("HEAD");

        r = Revision.parse("master");
        assertThat(r.getKind()).isEqualTo(Revision.Kind.REF);
        assertThat(refOf(r).name()).isEqualTo("master");

        r = Revision.parse("refs/heads/feature");
        assertThat(refOf(r).name()).isEqualTo("refs/heads/feature");

        r = Revision.parse("abc123def456");
        assertThat(refOf(r).name()).isEqualTo("abc123def456");
    }

    @Test
    @DisplayName("<rev>^ 解析为 Parent")
    void parse_caret_returnsParent() {
        Revision r = Revision.parse("HEAD^");
        assertThat(r.getKind()).isEqualTo(Revision.Kind.PARENT);
        assertThat(parentOf(r).rev().getKind()).isEqualTo(Revision.Kind.REF);
        assertThat(refOf(parentOf(r).rev()).name()).isEqualTo("HEAD");

        r = Revision.parse("@^");
        assertThat(r.getKind()).isEqualTo(Revision.Kind.PARENT);
        assertThat(parentOf(r).rev().getKind()).isEqualTo(Revision.Kind.REF);
        assertThat(refOf(parentOf(r).rev()).name()).isEqualTo("HEAD");
    }

    @Test
    @DisplayName("<rev>~<n> 解析为 Ancestor")
    void parse_tildeNumber_returnsAncestor() {
        Revision r = Revision.parse("HEAD~1");
        assertThat(r.getKind()).isEqualTo(Revision.Kind.ANCESTOR);
        assertThat(ancestorOf(r).n()).isEqualTo(1);
        assertThat(refOf(ancestorOf(r).rev()).name()).isEqualTo("HEAD");

        r = Revision.parse("master~42");
        assertThat(r.getKind()).isEqualTo(Revision.Kind.ANCESTOR);
        assertThat(ancestorOf(r).n()).isEqualTo(42);

        r = Revision.parse("@~");
        assertThat(r.getKind()).isEqualTo(Revision.Kind.ANCESTOR);
        assertThat(ancestorOf(r).n()).isEqualTo(1);
    }

    @Test
    @DisplayName("组合：HEAD^^ 与 HEAD~2")
    void parse_combinedCaretAndTilde() {
        Revision r = Revision.parse("HEAD^^");
        assertThat(r.getKind()).isEqualTo(Revision.Kind.PARENT);
        Revision.Parent p1 = parentOf(r);
        Revision.Parent p2 = parentOf(p1.rev());
        assertThat(refOf(p2.rev()).name()).isEqualTo("HEAD");

        r = Revision.parse("HEAD~2");
        assertThat(r.getKind()).isEqualTo(Revision.Kind.ANCESTOR);
        assertThat(ancestorOf(r).n()).isEqualTo(2);
    }

    @Test
    @DisplayName("组合：HEAD~2^ 即 (HEAD~2)^")
    void parse_tildeThenCaret() {
        Revision r = Revision.parse("HEAD~2^");
        assertThat(r.getKind()).isEqualTo(Revision.Kind.PARENT);
        Revision.Ancestor a = ancestorOf(parentOf(r).rev());
        assertThat(a.n()).isEqualTo(2);
        assertThat(refOf(a.rev()).name()).isEqualTo("HEAD");
    }

    @Test
    @DisplayName("组合：HEAD^~1 即 (HEAD^)~1")
    void parse_caretThenTilde() {
        Revision r = Revision.parse("HEAD^~1");
        assertThat(r.getKind()).isEqualTo(Revision.Kind.ANCESTOR);
        assertThat(ancestorOf(r).n()).isEqualTo(1);
        assertThat(parentOf(ancestorOf(r).rev()).getKind()).isEqualTo(Revision.Kind.PARENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    @DisplayName("空或仅空白抛出 RevisionParseException")
    void parse_emptyOrWhitespace_throws(String input) {
        assertThatThrownBy(() -> Revision.parse(input))
                .isInstanceOf(RevisionParseException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("null 抛出")
    void parse_null_throws() {
        assertThatThrownBy(() -> Revision.parse(null))
                .isInstanceOf(RevisionParseException.class);
    }


    @Test
    @DisplayName("Ancestor n<1 构造抛 IllegalArgumentException")
    void ancestor_nLessThanOne_throws() {
        assertThatThrownBy(() -> new Revision.Ancestor(new Revision.Ref("HEAD"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
