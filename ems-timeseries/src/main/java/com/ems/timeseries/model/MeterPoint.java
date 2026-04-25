package com.ems.timeseries.model;

import java.util.List;

/** 一个测点在某时间段内的所有时间桶。 */
public record MeterPoint(Long meterId, String meterCode, String energyTypeCode, List<TimePoint> points) {}
