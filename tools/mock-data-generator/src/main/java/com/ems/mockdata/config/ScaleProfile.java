package com.ems.mockdata.config;

/**
 * Controls how many meters / days / etc. are generated.
 * SMALL is for development validation; MEDIUM for integration; LARGE for perf testing.
 */
public enum ScaleProfile {

    SMALL(
        20,   // totalMeters
        10,   // electricMeters
        3,    // waterMeters
        2,    // steamMeters
        3,    // months of data
        1     // defaultMonths
    ),
    MEDIUM(
        120,  // totalMeters
        80,   // electricMeters
        15,   // waterMeters
        8,    // steamMeters
        3,    // months of data
        3     // defaultMonths
    ),
    LARGE(
        300,  // totalMeters
        200,  // electricMeters
        40,   // waterMeters
        20,   // steamMeters
        6,    // months of data
        6     // defaultMonths
    );

    private final int totalMeters;
    private final int electricMeters;
    private final int waterMeters;
    private final int steamMeters;
    private final int months;
    private final int defaultMonths;

    ScaleProfile(int totalMeters, int electricMeters, int waterMeters,
                 int steamMeters, int months, int defaultMonths) {
        this.totalMeters = totalMeters;
        this.electricMeters = electricMeters;
        this.waterMeters = waterMeters;
        this.steamMeters = steamMeters;
        this.months = months;
        this.defaultMonths = defaultMonths;
    }

    public int getTotalMeters()    { return totalMeters; }
    public int getElectricMeters() { return electricMeters; }
    public int getWaterMeters()    { return waterMeters; }
    public int getSteamMeters()    { return steamMeters; }
    public int getMonths()         { return months; }
    public int defaultMonths()     { return defaultMonths; }

    /** Remaining meters after electric/water/steam are gas+oil */
    public int getOtherMeters()    { return totalMeters - electricMeters - waterMeters - steamMeters; }
}
