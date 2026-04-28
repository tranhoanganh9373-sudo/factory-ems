package com.ems.tools.simulator;

import com.ghgande.j2mod.modbus.procimg.SimpleProcessImage;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地 Modbus TCP slave 模拟器 — 装机前演练 / 训练 / 故障复现用。
 *
 * <p>启动：
 * <pre>{@code
 *   # 先 install 依赖
 *   mvn -pl tools/modbus-simulator -am install -DskipTests -q
 *   # 模块内 exec
 *   cd tools/modbus-simulator && mvn exec:java -q
 *   #  默认 port=5502 unitId=1
 *
 *   mvn exec:java -Dexec.args="--port 502 --unit 3"
 * }</pre>
 *
 * <p>预置寄存器（与 deploy/collector.yml.example 对应）：
 * <ul>
 *   <li>0x2000..0x2001 — voltage_a (FLOAT32, V) — 220 ± 2 漂移</li>
 *   <li>0x2014..0x2015 — power_active (FLOAT32, W) — 5000 ± 500 漂移</li>
 *   <li>0x4000..0x4001 — energy_total (UINT32, 0.01 kWh 单位) — 单调递增</li>
 * </ul>
 *
 * <p>按 Ctrl-C 停止。
 */
public final class ModbusSimulator {

    public static void main(String[] args) throws Exception {
        int port = 5502;
        int unitId = 1;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--unit" -> unitId = Integer.parseInt(args[++i]);
                case "--help", "-h" -> { printUsage(); return; }
                default -> { System.err.println("unknown arg: " + args[i]); printUsage(); System.exit(2); }
            }
        }

        SimpleProcessImage img = new SimpleProcessImage();
        // SimpleProcessImage 按 add 顺序填 address 0,1,2,…  → 撑到 0x4002 = 16386 个 register
        for (int i = 0; i <= 0x4001; i++) {
            img.addRegister(new SimpleRegister(0));
        }

        // 初始值
        setFloat(img, 0x2000, 220.0f);          // voltage_a
        setFloat(img, 0x2014, 5000.0f);         // power_active (W)
        setUInt32(img, 0x4000, 1_000_000);      // energy_total raw=10000.00 kWh

        ModbusSlave slave = ModbusSlaveFactory.createTCPSlave(port, 5);
        slave.addProcessImage(unitId, img);
        slave.open();

        System.out.printf("[sim] Modbus TCP slave listening on 0.0.0.0:%d unit=%d%n", port, unitId);
        System.out.println("[sim] registers: 0x2000=voltage_a F32, 0x2014=power_active F32 (W), 0x4000=energy_total U32 (×0.01 kWh)");
        System.out.println("[sim] press Ctrl-C to stop");

        // 模拟漂移：voltage 正弦小幅、power 抖动、energy 单调递增
        final AtomicInteger tick = new AtomicInteger();
        final long energyStart = 1_000_000L;

        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sim-tick");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleAtFixedRate(() -> {
            int n = tick.incrementAndGet();
            float voltage = (float) (220.0 + 2.0 * Math.sin(n / 10.0));
            float power = (float) (5000.0 + 300.0 * Math.sin(n / 7.0) + (Math.random() - 0.5) * 200);
            // 能量按当前功率累加：每秒 power(W) / 3600 = Wh；× 100 转 0.01kWh raw 单位 / 1000 转 kWh
            // simplify：每 tick 增加 power/36 raw（≈ 1 秒 W → 0.01 kWh × 100）
            long energyRaw = energyStart + n * Math.max(1L, (long) (power / 36.0));
            setFloat(img, 0x2000, voltage);
            setFloat(img, 0x2014, power);
            setUInt32(img, 0x4000, energyRaw);
            if (n % 10 == 0) {
                System.out.printf("[sim] t=%ds V=%.2f P=%.0fW E_raw=%d (%.2f kWh)%n",
                        n, voltage, power, energyRaw, energyRaw / 100.0);
            }
        }, 1, 1, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[sim] shutting down...");
            exec.shutdownNow();
            slave.close();
        }, "sim-shutdown"));

        // 主线程挂住
        Thread.currentThread().join();
    }

    private static void setUInt32(SimpleProcessImage img, int addr, long value) {
        ((SimpleRegister) img.getRegister(addr)).setValue((short) ((value >>> 16) & 0xFFFF));
        ((SimpleRegister) img.getRegister(addr + 1)).setValue((short) (value & 0xFFFF));
    }

    private static void setFloat(SimpleProcessImage img, int addr, float value) {
        int bits = Float.floatToRawIntBits(value);
        setUInt32(img, addr, bits & 0xFFFFFFFFL);
    }

    private static void printUsage() {
        System.out.println("""
                Usage: ModbusSimulator [--port N] [--unit N]
                  --port  TCP port to listen on  (default 5502)
                  --unit  Modbus slave unit-id   (default 1)

                Pre-loaded registers:
                  0x2000  FLOAT32  voltage_a       (drifts 218..222 V)
                  0x2014  FLOAT32  power_active    (drifts 4500..5500 W)
                  0x4000  UINT32   energy_total    (×0.01 kWh, monotonic)
                """);
    }

    private ModbusSimulator() {}
}
