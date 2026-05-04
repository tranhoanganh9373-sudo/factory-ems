package com.ems.meter.entity;

/** Meter 物理角色。CONSUME=纯耗电、GENERATE=光伏/风电产出表、GRID_TIE=并网点。 */
public enum MeterRole {
    CONSUME,
    GENERATE,
    GRID_TIE
}
