package com.ems.collector.protocol;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MqttConfig Bean Validation")
class MqttConfigValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        factory.close();
    }

    private MqttConfig validBase(int qos) {
        return new MqttConfig(
            "tcp://broker:1883", "ems-test",
            null, null, null,
            qos, true, Duration.ofSeconds(60),
            null, null, 0, false,
            List.of(new MqttPoint("temp", "sensors/+/temp", "$.value", null, null))
        );
    }

    @Test
    @DisplayName("qos=0 通过校验")
    void qos0_passesValidation() {
        Set<ConstraintViolation<MqttConfig>> violations = validator.validate(validBase(0));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("qos=1 通过校验")
    void qos1_passesValidation() {
        Set<ConstraintViolation<MqttConfig>> violations = validator.validate(validBase(1));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("qos=2 通过校验（QoS 2 支持）")
    void qos2_passesValidation() {
        Set<ConstraintViolation<MqttConfig>> violations = validator.validate(validBase(2));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("qos=3 失败校验（超出范围）")
    void qos3_failsValidation() {
        Set<ConstraintViolation<MqttConfig>> violations = validator.validate(validBase(3));
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("qos");
    }

    @Test
    @DisplayName("qos=-1 失败校验（低于最小值）")
    void qosNegative1_failsValidation() {
        Set<ConstraintViolation<MqttConfig>> violations = validator.validate(validBase(-1));
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("qos");
    }

    // ---- LWT 校验测试 ----

    private MqttConfig validWithLwt(String topic, String payload, int lwtQos, boolean retained) {
        return new MqttConfig(
            "tcp://broker:1883", "ems-test",
            null, null, null,
            1, true, Duration.ofSeconds(60),
            topic, payload, lwtQos, retained,
            List.of(new MqttPoint("temp", "sensors/+/temp", "$.value", null, null))
        );
    }

    @Test
    @DisplayName("LWT 未配置（全默认）— 通过校验")
    void lastWillNotConfigured_passesValidation() {
        Set<ConstraintViolation<MqttConfig>> violations = validator.validate(validBase(1));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("LWT topic + payload 都设 — 通过校验")
    void lastWillFullyConfigured_passesValidation() {
        Set<ConstraintViolation<MqttConfig>> violations =
            validator.validate(validWithLwt("status/dead", "OFFLINE", 1, false));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("LWT topic 设 + payload 空 — 校验失败（部分配置）")
    void lastWillTopicOnly_failsValidation() {
        Set<ConstraintViolation<MqttConfig>> violations =
            validator.validate(validWithLwt("status/dead", null, 0, false));
        assertThat(violations).isNotEmpty();
        assertThat(violations.stream()
            .map(v -> v.getPropertyPath().toString())
            .anyMatch(p -> p.contains("lastWillConfigConsistent"))
        ).isTrue();
    }

    @Test
    @DisplayName("LWT topic 空 + payload 设 — 校验失败（部分配置）")
    void lastWillPayloadOnly_failsValidation() {
        Set<ConstraintViolation<MqttConfig>> violations =
            validator.validate(validWithLwt(null, "OFFLINE", 0, false));
        assertThat(violations).isNotEmpty();
        assertThat(violations.stream()
            .map(v -> v.getPropertyPath().toString())
            .anyMatch(p -> p.contains("lastWillConfigConsistent"))
        ).isTrue();
    }

    @Test
    @DisplayName("LWT qos=2 + 完整配置 — 通过校验")
    void lastWillQos2_passesValidation() {
        Set<ConstraintViolation<MqttConfig>> violations =
            validator.validate(validWithLwt("status/dead", "OFFLINE", 2, true));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("LWT lastWillQos=3 — 校验失败（超出范围）")
    void lastWillQos3_failsValidation() {
        Set<ConstraintViolation<MqttConfig>> violations =
            validator.validate(validWithLwt("status/dead", "OFFLINE", 3, false));
        assertThat(violations).isNotEmpty();
        assertThat(violations.stream()
            .map(v -> v.getPropertyPath().toString())
            .anyMatch(p -> p.equals("lastWillQos"))
        ).isTrue();
    }
}
