package com.weixiao.repo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefspecTest {

    @Test
    void parse_plus_prefix_and_mapping() {
        Refspec r = Refspec.parse("+refs/heads/*:refs/remotes/origin/*");
        assertThat(r.isForce()).isTrue();
        assertThat(r.getSource()).isEqualTo("refs/heads/*");
        assertThat(r.getDestination()).isEqualTo("refs/remotes/origin/*");
    }

    @Test
    void expand_with_wildcard() {
        Refspec spec = Refspec.parse("+refs/heads/*:refs/remotes/origin/*");
        java.util.List<String> remoteRefs = java.util.List.of("refs/heads/main", "refs/heads/dev");
        java.util.Map<String, Refspec.RefspecMapping> result = Refspec.expand(java.util.List.of(spec), remoteRefs);
        assertThat(result)
                .containsEntry("refs/heads/main", new Refspec.RefspecMapping("refs/remotes/origin/main", true))
                .containsEntry("refs/heads/dev", new Refspec.RefspecMapping("refs/remotes/origin/dev", true));
    }

    @Test
    void expand_without_wildcard() {
        Refspec spec = Refspec.parse("refs/heads/main:refs/remotes/origin/main");
        java.util.List<String> remoteRefs = java.util.List.of("refs/heads/main", "refs/heads/dev");
        java.util.Map<String, Refspec.RefspecMapping> result = Refspec.expand(java.util.List.of(spec), remoteRefs);
        assertThat(result)
                .containsEntry("refs/heads/main", new Refspec.RefspecMapping("refs/remotes/origin/main", false))
                .hasSize(1);
    }

    @Test
    void expand_multiple_specs() {
        Refspec spec1 = Refspec.parse("refs/heads/*:refs/remotes/origin/*");
        Refspec spec2 = Refspec.parse("+refs/tags/*:refs/tags/*");
        java.util.List<String> remoteRefs = java.util.List.of("refs/heads/main", "refs/tags/v1.0");
        java.util.Map<String, Refspec.RefspecMapping> result = Refspec.expand(java.util.List.of(spec1, spec2), remoteRefs);
        assertThat(result)
                .containsEntry("refs/heads/main", new Refspec.RefspecMapping("refs/remotes/origin/main", false))
                .containsEntry("refs/tags/v1.0", new Refspec.RefspecMapping("refs/tags/v1.0", true));
    }
}
