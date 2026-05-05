package com.ems.dashboard.service.impl;

import com.ems.core.config.PvFeatureProperties;
import com.ems.core.constant.ValueKind;
import com.ems.dashboard.dto.RangeQuery;
import com.ems.dashboard.dto.RangeType;
import com.ems.dashboard.support.DashboardSupport;
import com.ems.dashboard.support.MeterRecord;
import com.ems.floorplan.service.FloorplanService;
import com.ems.meter.entity.EnergySource;
import com.ems.meter.entity.FlowDirection;
import com.ems.meter.entity.MeterRole;
import com.ems.meter.repository.EnergyTypeRepository;
import com.ems.meter.repository.MeterTopologyRepository;
import com.ems.production.service.ProductionEntryService;
import com.ems.tariff.service.TariffService;
import com.ems.timeseries.query.TimeSeriesQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DashboardServiceImplPvKpiTest {

    @Mock private DashboardSupport support;
    @Mock private TimeSeriesQueryService tsq;
    @Mock private TariffService tariff;
    @Mock private EnergyTypeRepository energyTypes;
    @Mock private ProductionEntryService production;
    @Mock private MeterTopologyRepository topology;
    @Mock private FloorplanService floorplans;

    private DashboardServiceImpl service(boolean pvEnabled) {
        return new DashboardServiceImpl(support, tsq, tariff, energyTypes, production,
                                        topology, floorplans, new PvFeatureProperties(pvEnabled),
                                        mock(com.ems.dashboard.service.SolarSelfConsumptionService.class));
    }

    private MeterRecord meter(Long id, MeterRole role, EnergySource source, FlowDirection dir) {
        return new MeterRecord(id, "M" + id, "Meter " + id, 1L, "tag" + id,
            1L, "ELEC", "kWh", true, ValueKind.INTERVAL_DELTA, role, source, dir);
    }

    @Test
    void kpi_pvDisabled_returnsLegacyTotal() {
        var consume = meter(1L, MeterRole.CONSUME, EnergySource.GRID, FlowDirection.IMPORT);
        when(support.resolveMeters(any(), any())).thenReturn(List.of(consume));
        when(support.filterToVisibleRoots(any())).thenReturn(List.of(consume));
        when(tsq.sumByEnergyType(any(), any())).thenReturn(Map.of("ELEC", 1000.0));

        var result = service(false).kpi(new RangeQuery(RangeType.TODAY, null, null, 1L, "ELEC"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).total()).isEqualTo(1000.0);
        verify(support, times(1)).filterToVisibleRoots(any());
    }

    @Test
    void kpi_pvEnabled_appliesNewFormula() {
        var gridIn  = meter(1L, MeterRole.GRID_TIE, EnergySource.GRID, FlowDirection.IMPORT);
        var gridOut = meter(2L, MeterRole.GRID_TIE, EnergySource.GRID, FlowDirection.EXPORT);
        var solar   = meter(3L, MeterRole.GENERATE, EnergySource.SOLAR, FlowDirection.IMPORT);
        when(support.resolveMeters(any(), any())).thenReturn(List.of(gridIn, gridOut, solar));
        when(tsq.sumByEnergyType(argThat(refs -> refs != null && refs.size() == 1
                && refs.iterator().next().meterId() == 1L), any())).thenReturn(Map.of("ELEC", 1000.0));
        when(tsq.sumByEnergyType(argThat(refs -> refs != null && refs.size() == 1
                && refs.iterator().next().meterId() == 2L), any())).thenReturn(Map.of("ELEC", 200.0));
        when(tsq.sumByEnergyType(argThat(refs -> refs != null && refs.size() == 1
                && refs.iterator().next().meterId() == 3L), any())).thenReturn(Map.of("ELEC", 300.0));

        var result = service(true).kpi(new RangeQuery(RangeType.TODAY, null, null, 1L, "ELEC"));

        assertThat(result).hasSize(1);
        // total = 1000 - 200 + 300 = 1100
        assertThat(result.get(0).total()).isEqualTo(1100.0);
    }

    @Test
    void kpi_pvEnabled_noPvMeters_equalsConsumeSum() {
        var consume = meter(1L, MeterRole.CONSUME, EnergySource.GRID, FlowDirection.IMPORT);
        when(support.resolveMeters(any(), any())).thenReturn(List.of(consume));
        when(support.filterToVisibleRoots(any())).thenReturn(List.of(consume));
        when(tsq.sumByEnergyType(any(), any())).thenReturn(Map.of("ELEC", 800.0));

        var result = service(true).kpi(new RangeQuery(RangeType.TODAY, null, null, 1L, "ELEC"));

        assertThat(result.get(0).total()).isEqualTo(800.0);
    }
}
