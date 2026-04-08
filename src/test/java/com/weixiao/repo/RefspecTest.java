package com.weixiao.repo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefspecTest {

    @Test
    void parse_plus_prefix_and_mapping() {
        Refspec r = Refspec.parse("+refs/heads/*:refs/remotes/origin/*");
        assertThat(r.force()).isTrue();
        assertThat(r.source()).isEqualTo("refs/heads/*");
        assertThat(r.destination()).isEqualTo("refs/remotes/origin/*");
    }

    @Test
    void parse_without_colon() {
        Refspec r = Refspec.parse("refs/heads/main");
        assertThat(r.force()).isFalse();
        assertThat(r.source()).isEqualTo("refs/heads/main");
        assertThat(r.destination()).isNull();
    }

    @Test
    void empty_throws() {
        assertThatThrownBy(() -> Refspec.parse("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
