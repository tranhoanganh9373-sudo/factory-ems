package com.ems.collector.codec;

import com.ems.collector.config.ByteOrder;
import com.ems.collector.config.DataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 6 (DataType) × 4 (ByteOrder) = 24 个解码组合穷举。每个组合都喂"该顺序下的 wire bytes"，
 * 期望解码结果等于同一个目标值。也就是：configuring byte-order = X，意味着"meter 把字节
 * 摆成 X 的顺序"，decoder 内部 reorder 把它变回 ABCD 再标准解码。
 */
class RegisterDecoderTest {

    private static final HexFormat HEX = HexFormat.of();

    /** 4-byte target value 0xDEADBEEF (UINT32) / -559038737 (INT32) / FLOAT32 bits = 0xDEADBEEF (raw NaN). */
    private static byte[] hex(String s) { return HEX.parseHex(s.replace(" ", "")); }

    record Case(DataType type, ByteOrder order, byte[] wire, BigDecimal expected) {}

    static Stream<Case> all24Cases() {
        BigDecimal v_uint16 = BigDecimal.valueOf(0x1234);   // 4660
        BigDecimal v_int16  = BigDecimal.valueOf(-2);
        BigDecimal v_uint32 = new BigDecimal("3735928559"); // 0xDEADBEEF
        BigDecimal v_int32  = BigDecimal.valueOf(-2);       // 0xFFFFFFFE
        // 32-bit FLOAT 用一个有限可比较的值
        BigDecimal v_float32 = BigDecimal.valueOf((double) 3.14159f);
        // 64-bit FLOAT
        BigDecimal v_float64 = BigDecimal.valueOf(2.718281828459045d);

        return Stream.of(
                // ───── UINT16 ─────  wire bytes for 0x1234
                new Case(DataType.UINT16, ByteOrder.ABCD, hex("1234"), v_uint16),
                new Case(DataType.UINT16, ByteOrder.CDAB, hex("1234"), v_uint16),  // 16-bit CDAB no-op
                new Case(DataType.UINT16, ByteOrder.BADC, hex("3412"), v_uint16),
                new Case(DataType.UINT16, ByteOrder.DCBA, hex("3412"), v_uint16),

                // ───── INT16 ─────  wire bytes for -2 (0xFFFE)
                new Case(DataType.INT16, ByteOrder.ABCD, hex("FFFE"), v_int16),
                new Case(DataType.INT16, ByteOrder.CDAB, hex("FFFE"), v_int16),
                new Case(DataType.INT16, ByteOrder.BADC, hex("FEFF"), v_int16),
                new Case(DataType.INT16, ByteOrder.DCBA, hex("FEFF"), v_int16),

                // ───── UINT32 ─────  wire bytes for 0xDEADBEEF
                new Case(DataType.UINT32, ByteOrder.ABCD, hex("DEADBEEF"), v_uint32),
                new Case(DataType.UINT32, ByteOrder.CDAB, hex("BEEFDEAD"), v_uint32),
                new Case(DataType.UINT32, ByteOrder.BADC, hex("ADDEEFBE"), v_uint32),
                new Case(DataType.UINT32, ByteOrder.DCBA, hex("EFBEADDE"), v_uint32),

                // ───── INT32 ─────  wire bytes for -2 (0xFFFFFFFE)
                new Case(DataType.INT32, ByteOrder.ABCD, hex("FFFFFFFE"), v_int32),
                new Case(DataType.INT32, ByteOrder.CDAB, hex("FFFEFFFF"), v_int32),
                new Case(DataType.INT32, ByteOrder.BADC, hex("FFFFFEFF"), v_int32),
                new Case(DataType.INT32, ByteOrder.DCBA, hex("FEFFFFFF"), v_int32),

                // ───── FLOAT32 ─────  wire bytes for 3.14159f (IEEE-754 = 0x40490FD0)
                new Case(DataType.FLOAT32, ByteOrder.ABCD, hex("40490FD0"), v_float32),
                new Case(DataType.FLOAT32, ByteOrder.CDAB, hex("0FD04049"), v_float32),
                new Case(DataType.FLOAT32, ByteOrder.BADC, hex("4940D00F"), v_float32),
                new Case(DataType.FLOAT32, ByteOrder.DCBA, hex("D00F4940"), v_float32),

                // ───── FLOAT64 ─────  wire bytes for e (Math.E ≈ 2.718281828459045)
                //   IEEE-754 double bits = 0x4005BF0A8B145769
                new Case(DataType.FLOAT64, ByteOrder.ABCD, hex("4005BF0A8B145769"), v_float64),
                new Case(DataType.FLOAT64, ByteOrder.CDAB, hex("BF0A40058B145769".substring(0, 8) + "57698B14"),
                        v_float64),
                new Case(DataType.FLOAT64, ByteOrder.BADC, hex("0540 0ABF 148B 6957".replace(" ", "")), v_float64),
                new Case(DataType.FLOAT64, ByteOrder.DCBA, hex("69 57 14 8B 0A BF 05 40".replace(" ", "")), v_float64)
        );
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @MethodSource("all24Cases")
    void decode_24Combinations(Case c) {
        BigDecimal got = RegisterDecoder.decode(c.wire(), c.type(), c.order(), null);
        // FLOAT 类型用 doubleValue 比较容差；其他类型可整数严格相等
        if (c.type() == DataType.FLOAT32 || c.type() == DataType.FLOAT64) {
            assertThat(got.doubleValue())
                    .as("type=%s order=%s wire=%s", c.type(), c.order(), HEX.formatHex(c.wire()))
                    .isCloseTo(c.expected().doubleValue(), org.assertj.core.data.Offset.offset(1e-5));
        } else {
            assertThat(got).as("type=%s order=%s", c.type(), c.order())
                    .isEqualByComparingTo(c.expected());
        }
    }

    @Test
    void scale_isApplied() {
        // 32-bit value 100 with scale 0.01 → 1.0
        byte[] wire = hex("00000064"); // 100
        BigDecimal got = RegisterDecoder.decode(wire, DataType.UINT32, ByteOrder.ABCD,
                new BigDecimal("0.01"));
        assertThat(got).isEqualByComparingTo(new BigDecimal("1.00"));
    }

    @Test
    void scale_negative_isAccepted() {
        byte[] wire = hex("0064"); // 100
        BigDecimal got = RegisterDecoder.decode(wire, DataType.UINT16, ByteOrder.ABCD,
                new BigDecimal("-0.5"));
        assertThat(got).isEqualByComparingTo(new BigDecimal("-50.0"));
    }

    @Test
    void nullScale_treatedAsOne() {
        BigDecimal got = RegisterDecoder.decode(hex("0001"), DataType.UINT16, ByteOrder.ABCD, null);
        assertThat(got).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void wrongLength_throws() {
        assertThatThrownBy(() -> RegisterDecoder.decode(hex("01"), DataType.UINT16, ByteOrder.ABCD, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length=1");
        assertThatThrownBy(() -> RegisterDecoder.decode(hex("01020304"), DataType.UINT16, ByteOrder.ABCD, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bitType_throws() {
        assertThatThrownBy(() -> RegisterDecoder.decode(hex("0001"), DataType.BIT, ByteOrder.ABCD, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BIT");
    }

    @Test
    void int32_negativeMax() {
        // INT32 min = 0x80000000 = -2147483648
        byte[] wire = hex("80000000");
        BigDecimal got = RegisterDecoder.decode(wire, DataType.INT32, ByteOrder.ABCD, null);
        assertThat(got).isEqualByComparingTo(BigDecimal.valueOf(Integer.MIN_VALUE));
    }

    @Test
    void uint32_max() {
        // UINT32 max = 0xFFFFFFFF = 4294967295L
        byte[] wire = hex("FFFFFFFF");
        BigDecimal got = RegisterDecoder.decode(wire, DataType.UINT32, ByteOrder.ABCD, null);
        assertThat(got).isEqualByComparingTo(new BigDecimal("4294967295"));
    }
}
