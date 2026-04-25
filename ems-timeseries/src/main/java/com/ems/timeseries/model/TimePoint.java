package com.ems.timeseries.model;

import java.time.Instant;

/** 单测点单时间桶的聚合值。value 已按 granularity 聚合（sum or mean，由查询语义决定）。 */
public record TimePoint(Instant ts, double value) {}
