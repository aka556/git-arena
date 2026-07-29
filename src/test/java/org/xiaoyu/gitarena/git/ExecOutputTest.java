package org.xiaoyu.gitarena.git;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExecOutput} 工厂语义单测：ok/error 的字段归位，以及 null 归一为空串（防 NPE 泄漏到终端）。
 */
class ExecOutputTest {

    @Test
    void okCarriesStdoutAndEmptyStderr() {
        ExecOutput out = ExecOutput.ok("hello");

        assertThat(out.ok()).isTrue();
        assertThat(out.stdout()).isEqualTo("hello");
        assertThat(out.stderr()).isEmpty();
    }

    @Test
    void okNormalizesNullStdoutToEmpty() {
        assertThat(ExecOutput.ok(null).stdout()).isEmpty();
    }

    @Test
    void errorCarriesStderrAndEmptyStdout() {
        ExecOutput out = ExecOutput.error("boom");

        assertThat(out.ok()).isFalse();
        assertThat(out.stdout()).isEmpty();
        assertThat(out.stderr()).isEqualTo("boom");
    }

    @Test
    void errorNormalizesNullStderrToEmpty() {
        assertThat(ExecOutput.error(null).stderr()).isEmpty();
    }
}
