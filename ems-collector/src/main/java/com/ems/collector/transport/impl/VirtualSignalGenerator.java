package com.ems.collector.transport.impl;

import com.ems.collector.protocol.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class VirtualSignalGenerator {
    private final Map<String, Double> walkState = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public Object generate(VirtualPoint point, Instant now) {
        return switch (point.mode()) {
            case CONSTANT       -> point.params().getOrDefault("value", 0.0);
            case SINE           -> sine(point, now);
            case RANDOM_WALK    -> randomWalk(point);
            case CALENDAR_CURVE -> calendarCurve(point, now);
        };
    }

    private double sine(VirtualPoint p, Instant now) {
        double amp    = p.params().getOrDefault("amplitude", 1.0);
        double period = p.params().getOrDefault("periodSec", 60.0);
        double offset = p.params().getOrDefault("offset", 0.0);
        double t = (now.toEpochMilli() / 1000.0) % period;
        return amp * Math.sin(2 * Math.PI * t / period) + offset;
    }

    private double randomWalk(VirtualPoint p) {
        double min     = p.params().getOrDefault("min", 0.0);
        double max     = p.params().getOrDefault("max", 100.0);
        double maxStep = p.params().getOrDefault("maxStep", 1.0);
        double start   = p.params().getOrDefault("start", (min + max) / 2);
        var current = walkState.computeIfAbsent(p.key(), k -> start);
        double step = (random.nextDouble() * 2 - 1) * maxStep;
        double next = Math.max(min, Math.min(max, current + step));
        walkState.put(p.key(), next);
        return next;
    }

    private double calendarCurve(VirtualPoint p, Instant now) {
        double weekdayPeak = p.params().getOrDefault("weekdayPeak", 100.0);
        double weekendPeak = p.params().getOrDefault("weekendPeak", 30.0);
        double peakHour    = p.params().getOrDefault("peakHour", 9.0);
        double sigma       = p.params().getOrDefault("sigma", 3.0);
        var ldt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        boolean weekend = ldt.getDayOfWeek() == DayOfWeek.SATURDAY
                       || ldt.getDayOfWeek() == DayOfWeek.SUNDAY;
        double peak = weekend ? weekendPeak : weekdayPeak;
        double hour = ldt.getHour() + ldt.getMinute() / 60.0;
        double dist = hour - peakHour;
        return peak * Math.exp(-(dist * dist) / (2 * sigma * sigma));
    }
}
