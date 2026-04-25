package com.ems.timeseries.model;

public enum Granularity {
    MINUTE("1m"),
    HOUR("1h"),
    DAY("1d"),
    MONTH("1mo");

    private final String fluxWindow;
    Granularity(String fluxWindow) { this.fluxWindow = fluxWindow; }
    public String fluxWindow() { return fluxWindow; }
}
