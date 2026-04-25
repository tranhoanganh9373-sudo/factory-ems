package com.ems.tariff.entity;

public enum PeriodType {
    SHARP, PEAK, FLAT, VALLEY;

    public static boolean isValid(String value) {
        if (value == null) return false;
        for (PeriodType t : values()) {
            if (t.name().equals(value)) return true;
        }
        return false;
    }
}
