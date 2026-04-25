package com.ems.timeseries.rollup.dto;

public record BackfillResult(int meters, int buckets, int ok, int failed) {}
