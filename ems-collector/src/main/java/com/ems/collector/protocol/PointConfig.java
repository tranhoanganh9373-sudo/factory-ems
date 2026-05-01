package com.ems.collector.protocol;

public sealed interface PointConfig
    permits ModbusPoint, OpcUaPoint, MqttPoint, VirtualPoint {
    String key();
    String unit();
}
