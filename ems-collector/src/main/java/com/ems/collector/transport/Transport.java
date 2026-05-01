package com.ems.collector.transport;

import com.ems.collector.protocol.ChannelConfig;

/**
 * 统一传输抽象 — Modbus / OPC UA / MQTT / VIRTUAL 五种协议的运行时门面。
 *
 * <p>线程模型：
 * <ul>
 *   <li>{@link #start(Long, ChannelConfig, SampleSink)} 在调用线程同步执行连接 + 启动后台采集线程</li>
 *   <li>采集到的样本通过 {@link SampleSink#accept(Sample)} 异步回调（实现需线程安全）</li>
 *   <li>{@link #stop()} 必须幂等</li>
 *   <li>{@link #testConnection(ChannelConfig)} 同步测试，不持久化连接</li>
 * </ul>
 *
 * <p>每个 channel 有专属 Transport 实例，不在多 channel 间复用。
 *
 * <p>Note: 设计上为 sealed（permits ModbusTcpAdapterTransport / ModbusRtuAdapterTransport /
 * OpcUaTransport / MqttTransport / VirtualTransport 五种），但 Java 25 + Mockito 5.11
 * 不兼容 sealed 类型 mock，故运行时改回 plain interface — 实际仍只有这 5 个生产实现。
 */
public interface Transport {

    /**
     * 启动 transport：建立连接 + 调度后台采集。
     *
     * @throws TransportException 连接 / 启动失败
     */
    void start(Long channelId, ChannelConfig config, SampleSink sink) throws TransportException;

    /** 停止采集并释放资源。幂等。 */
    void stop();

    /** 当前连接是否健康（仅供 UI 状态显示参考，不保证下次 read 一定成功）。 */
    boolean isConnected();

    /**
     * 临时测试连接（不影响 active transport 状态）。10s 超时建议。
     *
     * <p>实现可以临时构造 client → connect → close，不复用任何 instance state。
     */
    TestResult testConnection(ChannelConfig config);
}
