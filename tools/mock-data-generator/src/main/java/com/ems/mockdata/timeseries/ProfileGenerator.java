package com.ems.mockdata.timeseries;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Returns a base load multiplier [0.0, 1.0] for a given (energyTypeCode, hour-of-day, datetime).
 * All values are deterministic — no Random injected here.
 */
public class ProfileGenerator {

    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    // 2026 Chinese public holidays — hardcoded for MVP
    // Format: MMDD
    private static final int[] HOLIDAYS_2026 = {
        101, 102, 103,           // New Year
        219, 220, 221, 222, 223, 224, 225, 226, // Spring Festival
        404, 405, 406,           // Qingming
        501, 502, 503, 504, 505, // Labour Day
        619, 620, 621,           // Dragon Boat
        1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008  // National Day
    };

    /**
     * Base load factor for electric meters (industrial pattern).
     * Peak: 08-12, 14-18. Low: 00-06.
     */
    public double electricFactor(LocalDateTime ldt) {
        int h = ldt.getHour();
        double hourFactor;
        if (h >= 9 && h < 11) {
            hourFactor = 1.0;   // sharp
        } else if ((h >= 8 && h < 9) || (h >= 11 && h < 12) || (h >= 17 && h < 21)) {
            hourFactor = 0.85;  // peak
        } else if ((h >= 12 && h < 17) || (h >= 21 && h < 22) || (h >= 6 && h < 8)) {
            hourFactor = 0.65;  // flat
        } else {
            hourFactor = 0.30;  // valley 22-06
        }

        double dayFactor = isHolidayOrWeekend(ldt) ? 0.45 : 1.0;
        double seasonFactor = seasonalFactor(ldt);
        return hourFactor * dayFactor * seasonFactor;
    }

    /**
     * Base load factor for water meters.
     */
    public double waterFactor(LocalDateTime ldt) {
        int h = ldt.getHour();
        double hourFactor = (h >= 7 && h < 20) ? 0.7 : 0.3;
        double dayFactor = isHolidayOrWeekend(ldt) ? 0.5 : 1.0;
        return hourFactor * dayFactor * seasonalFactor(ldt);
    }

    /**
     * Base load factor for steam/gas/other meters.
     */
    public double steamFactor(LocalDateTime ldt) {
        int h = ldt.getHour();
        double hourFactor = (h >= 8 && h < 18) ? 0.8 : 0.35;
        double dayFactor = isHolidayOrWeekend(ldt) ? 0.40 : 1.0;
        return hourFactor * dayFactor * seasonalFactor(ldt);
    }

    /** Seasonal adjustment: winter(Jan/Feb) +10%, summer(Jul/Aug) +5%, other neutral. */
    private double seasonalFactor(LocalDateTime ldt) {
        int m = ldt.getMonthValue();
        if (m == 1 || m == 2) return 1.10;
        if (m == 7 || m == 8) return 1.05;
        if (m == 4 || m == 10) return 0.95;
        return 1.0;
    }

    private boolean isHolidayOrWeekend(LocalDateTime ldt) {
        DayOfWeek dow = ldt.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return true;
        int mmdd = ldt.getMonthValue() * 100 + ldt.getDayOfMonth();
        for (int h : HOLIDAYS_2026) {
            if (h == mmdd) return true;
        }
        return false;
    }
}
