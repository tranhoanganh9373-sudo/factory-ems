package com.ems.dashboard.service.impl;

import com.ems.core.config.PvFeatureProperties;
import com.ems.core.constant.ValueKind;
import com.ems.dashboard.dto.EnergySourceMixDTO;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceImplEnergyMixTest {

    @Mock private DashboardSupport support;
    @Mock private TimeSeriesQueryService tsq;
    @Mock private TariffService tariff;
    @Mock private EnergyTypeRepository energyTypes;
    @Mock private ProductionEntryService production;
    @Mock private MeterTopologyRepository topology;
    @Mock private FloorplanService floorplans;

    private DashboardServiceImpl service(boolean pvEnabled) {
        return new DashboardServiceImpl(support, tsq, tariff, energyTypes, production,
                                        topology, floorplans, new PvFeatureProperties(pvEnabled));
    }

    private MeterRecord meter(Long id, MeterRole role, EnergySource source, FlowDirection dir) {
        return new MeterRecord(id, "M" + id, "Meter " + id, 1L, "tag" + id,
            1L, "ELEC", "kWh", true, ValueKind.INTERVAL_DELTA, role, source, dir);
    }

    @Test
    void energySourceMix_pvEnabled_groupsBySource() {
        var grid  = meter(1L, MeterRole.GRID_TIE,  EnergySource.GRID,  FlowDirection.IMPORT);
        var solar = meter(2L, MeterRole.GENERATE, EnergySource.SOLAR, FlowDirection.IMPORT);
        when(support.resolveMeters(any(), any())).thenReturn(List.of(grid, solar));
        when(support.filterToVisibleRoots(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tsq.sumByEnergyType(argThat(r -> r != null && r.size() == 1
                && r.iterator().next().meterId() == 1L), any())).thenReturn(Map.of("ELEC", 800.0));
        when(tsq.sumByEnergyType(argThat(r -> r != null && r.size() == 1
                && r.iterator().next().meterId() == 2L), any())).thenReturn(Map.of("ELEC", 200.0));

        var result = service(true).energySourceMix(new RangeQuery(RangeType.TODAY, null, null, 1L, null));

        assertThat(result).hasSize(2);
        assertThat(result).extracting("energySource")
            .containsExactlyInAnyOrder(EnergySource.GRID, EnergySource.SOLAR);
        var gridEntry = result.stream()
            .filter(d -> d.energySource() == EnergySource.GRID)
            .findFirst().orElseThrow();
        assertThat(gridEntry.share()).isEqualTo(0.8);
        assertThat(gridEntry.value()).isEqualTo(800.0);
    }

    @Test
    void energySourceMix_pvDisabled_returnsEmpty() {
        var result = service(false).energySourceMix(new RangeQuery(RangeType.TODAY, null, null, 1L, null));
        assertThat(result).isEmpty();
    }

    @Test
    void energySourceMix_emptyMeters_returnsEmpty() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of());
        var result = service(true).energySourceMix(new RangeQuery(RangeType.TODAY, null, null, 1L, null));
        assertThat(result).isEmpty();
    }
}
