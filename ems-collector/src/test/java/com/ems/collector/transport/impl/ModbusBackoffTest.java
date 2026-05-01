package com.ems.collector.transport.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ModbusBackoff — exponential 1s..60s cap")
class ModbusBackoffTest {

    @ParameterizedTest(name = "attempts={0} → {1}ms")
    @CsvSource({
            "0, 1000",
            "1, 2000",
            "2, 4000",
            "3, 8000",
            "4, 16000",
            "5, 32000",
            "6, 60000",
            "7, 60000",
            "100, 60000"
    })
    @DisplayName("nextDelayMs follows 1s→2s→4s→8s→16s→32s→60s and caps at 60s")
    void nextDelayMs_followsExpectedSequenceAndCaps(int attempts, long expectedMs) {
        assertThat(ModbusBackoff.nextDelayMs(attempts)).isEqualTo(expectedMs);
    }

    @Test
    @DisplayName("nextDelayMs treats negative attempts as zero (defensive)")
    void nextDelayMs_negativeAttempts_treatedAsZero() {
        assertThat(ModbusBackoff.nextDelayMs(-1)).isEqualTo(1000L);
        assertThat(ModbusBackoff.nextDelayMs(-100)).isEqualTo(1000L);
    }
}
