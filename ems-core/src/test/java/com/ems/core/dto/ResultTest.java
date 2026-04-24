package com.ems.core.dto;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {
    @Test
    void ok_shouldCarryPayload() {
        Result<String> r = Result.ok("hello");
        assertThat(r.code()).isEqualTo(0);
        assertThat(r.data()).isEqualTo("hello");
        assertThat(r.message()).isEqualTo("ok");
    }

    @Test
    void error_shouldCarryCodeAndMessage() {
        Result<?> r = Result.error(40001, "unauthorized");
        assertThat(r.code()).isEqualTo(40001);
        assertThat(r.message()).isEqualTo("unauthorized");
        assertThat(r.data()).isNull();
    }
}
