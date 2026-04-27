package com.ems.collector.codec;

import com.ems.collector.config.ByteOrder;
import com.ems.collector.config.DataType;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 把 j2mod 读上来的 big-endian 寄存器字节流，按配置的 {@link ByteOrder} 重排，再按 {@link DataType}
 * 解码成 {@link BigDecimal}，最后乘 scale。
 *
 * <p>纯函数，无 IO，无 j2mod 依赖。
 *
 * <p>byte-order 语义（约定字节宽度 N=2/4/8）：
 * <ul>
 *   <li>{@link ByteOrder#ABCD} — 不重排（IEEE 标准大端）</li>
 *   <li>{@link ByteOrder#DCBA} — 整体反转（小端，少见）</li>
 *   <li>{@link ByteOrder#CDAB} — 每 4 字节为一段，段内 16-bit 字两两交换；段间不动</li>
 *   <li>{@link ByteOrder#BADC} — 每 16-bit 字内字节交换；字间不动</li>
 * </ul>
 *
 * <p>对 16-bit 数据：ABCD == CDAB（无操作），BADC == DCBA（字节交换）。
 * 这是常见国产电表 word-swap 行为的最小可表达模型。
 */
public final class RegisterDecoder {

    private RegisterDecoder() {}

    /**
     * @param raw   从 j2mod 读到的原始 big-endian 字节流，长度必须等于 {@code type.wordCount() * 2}
     * @param type  目标解码类型（不允许 BIT，BIT 走 readCoil/readDiscreteInputs）
     * @param order 配置中声明的字节序；null 当作 ABCD
     * @param scale 缩放系数；null 当作 1
     * @return 解码后的 BigDecimal（scale 自动跟 BigDecimal 算子规则保留）
     */
    public static BigDecimal decode(byte[] raw, DataType type, ByteOrder order, BigDecimal scale) {
        Objects.requireNonNull(raw, "raw bytes required");
        Objects.requireNonNull(type, "dataType required");
        if (type == DataType.BIT) {
            throw new IllegalArgumentException(
                    "BIT type is decoded by readCoils/readDiscreteInputs, not RegisterDecoder");
        }
        int expected = type.wordCount() * 2;
        if (raw.length != expected) {
            throw new IllegalArgumentException(
                    "raw bytes length=" + raw.length + " mismatches dataType=" + type
                            + " (expected " + expected + ")");
        }

        byte[] b = reorder(raw, order == null ? ByteOrder.ABCD : order);
        BigDecimal value = switch (type) {
            case UINT16 -> BigDecimal.valueOf(
                    ((b[0] & 0xFF) << 8) | (b[1] & 0xFF));
            case INT16  -> BigDecimal.valueOf(
                    (short) (((b[0] & 0xFF) << 8) | (b[1] & 0xFF)));
            case UINT32 -> BigDecimal.valueOf(
                    ((long) (b[0] & 0xFF) << 24)
                            | ((long) (b[1] & 0xFF) << 16)
                            | ((long) (b[2] & 0xFF) << 8)
                            | ((long) (b[3] & 0xFF)));
            case INT32  -> BigDecimal.valueOf(
                    ((b[0] & 0xFF) << 24)
                            | ((b[1] & 0xFF) << 16)
                            | ((b[2] & 0xFF) << 8)
                            | (b[3] & 0xFF));
            case FLOAT32 -> {
                int bits = ((b[0] & 0xFF) << 24)
                        | ((b[1] & 0xFF) << 16)
                        | ((b[2] & 0xFF) << 8)
                        | (b[3] & 0xFF);
                yield BigDecimal.valueOf(Float.intBitsToFloat(bits));
            }
            case FLOAT64 -> {
                long bits = 0L;
                for (int i = 0; i < 8; i++) bits = (bits << 8) | (b[i] & 0xFFL);
                yield BigDecimal.valueOf(Double.longBitsToDouble(bits));
            }
            case BIT -> throw new IllegalStateException("unreachable");
        };

        BigDecimal s = scale == null ? BigDecimal.ONE : scale;
        return value.multiply(s);
    }

    /**
     * 按 byte-order 重排原始字节流。在 byte-order 为 ABCD 时直接返回输入引用副本。
     *
     * <p>包级可见以便单测穷举。
     */
    static byte[] reorder(byte[] in, ByteOrder order) {
        int n = in.length;
        byte[] out = new byte[n];
        switch (order) {
            case ABCD -> System.arraycopy(in, 0, out, 0, n);
            case DCBA -> {
                for (int i = 0; i < n; i++) out[i] = in[n - 1 - i];
            }
            case CDAB -> {
                // 每 4 字节一段，段内 16-bit 字两两交换
                int i = 0;
                while (i + 4 <= n) {
                    out[i]     = in[i + 2];
                    out[i + 1] = in[i + 3];
                    out[i + 2] = in[i];
                    out[i + 3] = in[i + 1];
                    i += 4;
                }
                while (i < n) {
                    out[i] = in[i];
                    i++;
                }
            }
            case BADC -> {
                // 每 16-bit 字内字节交换
                int i = 0;
                while (i + 2 <= n) {
                    out[i]     = in[i + 1];
                    out[i + 1] = in[i];
                    i += 2;
                }
                while (i < n) {
                    out[i] = in[i];
                    i++;
                }
            }
        }
        return out;
    }
}
