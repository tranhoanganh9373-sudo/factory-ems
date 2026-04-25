package com.ems.dashboard.service;

import com.ems.dashboard.dto.*;
import com.ems.dashboard.service.impl.DashboardServiceImpl;
import com.ems.dashboard.support.DashboardSupport;
import com.ems.dashboard.support.MeterRecord;
import com.ems.floorplan.dto.FloorplanDTO;
import com.ems.floorplan.dto.FloorplanPointDTO;
import com.ems.floorplan.dto.FloorplanWithPointsDTO;
import com.ems.floorplan.service.FloorplanService;
import com.ems.meter.entity.MeterTopology;
import com.ems.meter.repository.EnergyTypeRepository;
import com.ems.meter.repository.MeterTopologyRepository;
import com.ems.production.service.ProductionEntryService;
import com.ems.tariff.service.TariffService;
import com.ems.timeseries.model.MeterPoint;
import com.ems.timeseries.model.TimePoint;
import com.ems.timeseries.model.TimeRange;
import com.ems.timeseries.query.TimeSeriesQueryService;
import com.ems.timeseries.query.TimeSeriesQueryService.MeterRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashboardServiceImplTest {

    DashboardSupport support;
    TimeSeriesQueryService tsq;
    TariffService tariff;
    EnergyTypeRepository energyTypes;
    ProductionEntryService production;
    MeterTopologyRepository topology;
    FloorplanService floorplans;
    DashboardServiceImpl svc;

    static final MeterRecord M1 = new MeterRecord(1L, "M-1", "总表-电", 10L, "M-1", 1L, "ELEC", "kWh", true);
    static final MeterRecord M2 = new MeterRecord(2L, "M-2", "总表-水", 10L, "M-2", 2L, "WATER", "m³", true);
    static final MeterRecord M3 = new MeterRecord(3L, "M-3", "支表-电", 11L, "M-3", 1L, "ELEC", "kWh", true);

    @BeforeEach
    void setup() {
        support = mock(DashboardSupport.class);
        tsq = mock(TimeSeriesQueryService.class);
        tariff = mock(TariffService.class);
        energyTypes = mock(EnergyTypeRepository.class);
        production = mock(ProductionEntryService.class);
        topology = mock(MeterTopologyRepository.class);
        floorplans = mock(FloorplanService.class);
        svc = new DashboardServiceImpl(support, tsq, tariff, energyTypes, production, topology, floorplans);
    }

    /* ---------------- KPI ---------------- */

    @Test
    void kpi_computesMomYoy_correctly() {
        when(support.resolveMeters(eq(10L), isNull())).thenReturn(List.of(M1, M2));
        // 三次连续调用：cur → prev (mom) → prevYear (yoy)
        when(tsq.sumByEnergyType(anyCollection(), any(TimeRange.class)))
            .thenReturn(
                Map.of("ELEC", 120.0, "WATER", 50.0),  // cur
                Map.of("ELEC", 100.0, "WATER", 25.0),  // prev (mom)
                Map.of("ELEC", 80.0, "WATER", 20.0)    // prevYear (yoy)
            );

        var out = svc.kpi(new RangeQuery(RangeType.TODAY, null, null, 10L, null));

        assertThat(out).hasSize(2);
        var elec = out.stream().filter(k -> "ELEC".equals(k.energyType())).findFirst().orElseThrow();
        assertThat(elec.total()).isEqualTo(120.0);
        assertThat(elec.unit()).isEqualTo("kWh");
        assertThat(elec.mom()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(elec.yoy()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));

        var water = out.stream().filter(k -> "WATER".equals(k.energyType())).findFirst().orElseThrow();
        assertThat(water.mom()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void kpi_returnsEmpty_whenNoMeters() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of());
        assertThat(svc.kpi(new RangeQuery(RangeType.TODAY, null, null, null, null))).isEmpty();
        verify(tsq, never()).sumByEnergyType(any(), any());
    }

    @Test
    void kpi_nullPrev_yieldsNullMom() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of(M1));
        when(tsq.sumByEnergyType(anyCollection(), any(TimeRange.class)))
            .thenReturn(
                Map.of("ELEC", 100.0),
                Map.of(),               // prev empty → mom = null
                Map.of()                // prevYear empty → yoy = null
            );

        var out = svc.kpi(new RangeQuery(RangeType.TODAY, null, null, null, null));
        assertThat(out).singleElement().satisfies(k -> {
            assertThat(k.total()).isEqualTo(100.0);
            assertThat(k.mom()).isNull();
            assertThat(k.yoy()).isNull();
        });
    }

    /* ---------------- realtime-series ---------------- */

    @Test
    void realtimeSeries_groupsByEnergyType_andSumsAcrossMeters() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of(M1, M2, M3));
        Instant t0 = Instant.parse("2026-04-25T00:00:00Z");
        Instant t1 = Instant.parse("2026-04-25T01:00:00Z");

        when(tsq.queryByMeter(anyCollection(), any(), any())).thenReturn(List.of(
            new MeterPoint(1L, "M-1", "ELEC", List.of(new TimePoint(t0, 10.0), new TimePoint(t1, 20.0))),
            new MeterPoint(2L, "M-2", "WATER", List.of(new TimePoint(t0, 5.0))),
            new MeterPoint(3L, "M-3", "ELEC", List.of(new TimePoint(t0, 3.0), new TimePoint(t1, 7.0)))
        ));

        var out = svc.realtimeSeries(new RangeQuery(RangeType.LAST_24H, null, null, null, null));
        assertThat(out).hasSize(2);
        var elec = out.stream().filter(s -> "ELEC".equals(s.energyType())).findFirst().orElseThrow();
        assertThat(elec.unit()).isEqualTo("kWh");
        assertThat(elec.points()).hasSize(2);
        assertThat(elec.points().get(0).ts()).isEqualTo(t0);
        assertThat(elec.points().get(0).value()).isEqualTo(13.0);
        assertThat(elec.points().get(1).value()).isEqualTo(27.0);

        var water = out.stream().filter(s -> "WATER".equals(s.energyType())).findFirst().orElseThrow();
        assertThat(water.points()).singleElement().satisfies(p -> assertThat(p.value()).isEqualTo(5.0));
    }

    /* ---------------- composition ---------------- */

    @Test
    void energyComposition_returnsShare_summingTo1() {
        when(support.resolveMeters(any(), isNull())).thenReturn(List.of(M1, M2));
        when(tsq.sumByEnergyType(anyCollection(), any())).thenReturn(Map.of("ELEC", 75.0, "WATER", 25.0));

        var out = svc.energyComposition(new RangeQuery(RangeType.TODAY, null, null, null, "ELEC"));
        // composition 不再受 energyType 过滤
        verify(support).resolveMeters(any(), isNull());

        assertThat(out).hasSize(2);
        double total = out.stream().mapToDouble(CompositionDTO::share).sum();
        assertThat(total).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        var elec = out.stream().filter(c -> "ELEC".equals(c.energyType())).findFirst().orElseThrow();
        assertThat(elec.total()).isEqualTo(75.0);
        assertThat(elec.share()).isCloseTo(0.75, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void energyComposition_zeroSum_yieldsNullShare() {
        when(support.resolveMeters(any(), isNull())).thenReturn(List.of(M1));
        when(tsq.sumByEnergyType(anyCollection(), any())).thenReturn(Map.of("ELEC", 0.0));

        var out = svc.energyComposition(new RangeQuery(RangeType.TODAY, null, null, null, null));
        assertThat(out).singleElement().satisfies(c -> assertThat(c.share()).isNull());
    }

    /* ---------------- meter detail ---------------- */

    @Test
    void meterDetail_returnsTotalAndSeries() {
        when(support.resolveOneMeter(1L)).thenReturn(M1);
        Instant t = Instant.parse("2026-04-25T00:00:00Z");
        when(tsq.queryByMeter(anyCollection(), any(), any())).thenReturn(List.of(
            new MeterPoint(1L, "M-1", "ELEC", List.of(new TimePoint(t, 42.0)))
        ));
        when(tsq.sumByMeter(anyCollection(), any())).thenReturn(Map.of(1L, 99.5));

        var out = svc.meterDetail(1L, new RangeQuery(RangeType.TODAY, null, null, null, null));
        assertThat(out.meterId()).isEqualTo(1L);
        assertThat(out.energyTypeCode()).isEqualTo("ELEC");
        assertThat(out.total()).isEqualTo(99.5);
        assertThat(out.series()).hasSize(1);
        assertThat(out.series().get(0).value()).isEqualTo(42.0);
    }

    /* ---------------- top-n ---------------- */

    @Test
    void topN_sortsDescending_andTrimsToLimit() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of(M1, M2, M3));
        when(tsq.sumByMeter(anyCollection(), any())).thenReturn(Map.of(
            1L, 100.0, 2L, 50.0, 3L, 200.0
        ));

        var out = svc.topN(new RangeQuery(RangeType.TODAY, null, null, null, null), 2);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).meterId()).isEqualTo(3L);
        assertThat(out.get(0).total()).isEqualTo(200.0);
        assertThat(out.get(1).meterId()).isEqualTo(1L);
    }

    @Test
    void topN_zeroLimit_defaultsTo10() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of(M1, M2));
        when(tsq.sumByMeter(anyCollection(), any())).thenReturn(Map.of(1L, 1.0, 2L, 2.0));
        assertThat(svc.topN(new RangeQuery(RangeType.TODAY, null, null, null, null), 0)).hasSize(2);
    }

    @Test
    void topN_includesZeroValueMeters_atBottom() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of(M1, M2));
        when(tsq.sumByMeter(anyCollection(), any())).thenReturn(Map.of(2L, 5.0));
        // M1 没有数据 → total = 0，应排在 M2 后面
        var out = svc.topN(new RangeQuery(RangeType.TODAY, null, null, null, null), 10);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).meterId()).isEqualTo(2L);
        assertThat(out.get(1).meterId()).isEqualTo(1L);
        assertThat(out.get(1).total()).isEqualTo(0.0);
    }

    /* ---------------- ⑥ Tariff distribution ---------------- */

    @Test
    void tariffDistribution_bucketsByPeriodType_andComputesShare() {
        when(support.resolveMeters(any(), eq("ELEC"))).thenReturn(List.of(M1, M3));
        com.ems.meter.entity.EnergyType elec = mock(com.ems.meter.entity.EnergyType.class);
        when(elec.getId()).thenReturn(1L);
        when(energyTypes.findByCode("ELEC")).thenReturn(java.util.Optional.of(elec));

        Instant t1 = Instant.parse("2026-04-25T00:00:00Z"); // VALLEY
        Instant t2 = Instant.parse("2026-04-25T10:00:00Z"); // PEAK
        Instant t3 = Instant.parse("2026-04-25T18:00:00Z"); // SHARP
        when(tsq.queryByMeter(anyCollection(), any(), any())).thenReturn(List.of(
            new MeterPoint(1L, "M-1", "ELEC", List.of(
                new TimePoint(t1, 10.0), new TimePoint(t2, 30.0), new TimePoint(t3, 20.0)
            ))
        ));
        when(tariff.resolvePeriodType(eq(1L), argThat(o -> o.toInstant().equals(t1)))).thenReturn("VALLEY");
        when(tariff.resolvePeriodType(eq(1L), argThat(o -> o.toInstant().equals(t2)))).thenReturn("PEAK");
        when(tariff.resolvePeriodType(eq(1L), argThat(o -> o.toInstant().equals(t3)))).thenReturn("SHARP");

        var out = svc.tariffDistribution(new RangeQuery(RangeType.TODAY, null, null, null, null));

        assertThat(out.unit()).isEqualTo("kWh");
        assertThat(out.total()).isEqualTo(60.0);
        assertThat(out.slices()).hasSize(4);
        assertThat(out.slices().get(0).periodType()).isEqualTo("SHARP");
        assertThat(out.slices().get(0).value()).isEqualTo(20.0);
        assertThat(out.slices().get(0).share()).isEqualTo(20.0 / 60.0);
    }

    @Test
    void tariffDistribution_emptyMeters_returnsZero() {
        when(support.resolveMeters(any(), eq("ELEC"))).thenReturn(List.of());
        var out = svc.tariffDistribution(new RangeQuery(RangeType.TODAY, null, null, null, null));
        assertThat(out.total()).isEqualTo(0.0);
        assertThat(out.slices()).isEmpty();
    }

    /* ---------------- ⑧ Sankey ---------------- */

    private static MeterTopology edge(Long parent, Long child) {
        MeterTopology e = new MeterTopology();
        e.setParentMeterId(parent);
        e.setChildMeterId(child);
        return e;
    }

    @Test
    void sankey_buildsNodesAndLinks_fromTopology() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of(M1, M3));
        when(tsq.sumByMeter(anyCollection(), any())).thenReturn(Map.of(1L, 100.0, 3L, 30.0));
        when(topology.findAll()).thenReturn(List.of(edge(1L, 3L)));

        var out = svc.sankey(new RangeQuery(RangeType.TODAY, null, null, null, null));

        assertThat(out.nodes()).hasSize(2);
        assertThat(out.nodes()).extracting(SankeyDTO.Node::id).containsExactlyInAnyOrder("1", "3");
        assertThat(out.links()).singleElement().satisfies(l -> {
            assertThat(l.source()).isEqualTo("1");
            assertThat(l.target()).isEqualTo("3");
            assertThat(l.value()).isEqualTo(30.0);  // child 累计
        });
    }

    @Test
    void sankey_filtersOutEdges_outsideVisibleScope() {
        // 仅 M1 可见；topology 中 5L 不在可见集合中 → 边丢弃
        when(support.resolveMeters(any(), any())).thenReturn(List.of(M1));
        when(tsq.sumByMeter(anyCollection(), any())).thenReturn(Map.of(1L, 50.0));
        when(topology.findAll()).thenReturn(List.of(edge(1L, 5L), edge(99L, 1L)));

        var out = svc.sankey(new RangeQuery(RangeType.TODAY, null, null, null, null));
        assertThat(out.nodes()).isEmpty();
        assertThat(out.links()).isEmpty();
    }

    @Test
    void sankey_emptyMeters_returnsEmpty() {
        when(support.resolveMeters(any(), any())).thenReturn(List.of());
        var out = svc.sankey(new RangeQuery(RangeType.TODAY, null, null, null, null));
        assertThat(out.nodes()).isEmpty();
        assertThat(out.links()).isEmpty();
        verify(topology, never()).findAll();
    }

    /* ---------------- ⑨ Floorplan live ---------------- */

    private static FloorplanWithPointsDTO fpFixture(Long fpId, FloorplanPointDTO... pts) {
        FloorplanDTO fp = new FloorplanDTO(fpId, "车间A", 10L, "image/png",
                1024, 768, 1234L, true, OffsetDateTime.parse("2026-04-25T08:00:00Z"));
        return new FloorplanWithPointsDTO(fp, List.of(pts));
    }

    @Test
    void floorplanLive_returnsPointsWithLevels() {
        FloorplanPointDTO p1 = new FloorplanPointDTO(101L, 1L, new BigDecimal("0.10"), new BigDecimal("0.20"), "A");
        FloorplanPointDTO p2 = new FloorplanPointDTO(102L, 3L, new BigDecimal("0.30"), new BigDecimal("0.40"), "B");
        when(floorplans.getById(7L)).thenReturn(fpFixture(7L, p1, p2));
        when(support.resolveMeters(any(), isNull())).thenReturn(List.of(M1, M3));
        when(tsq.sumByMeter(anyCollection(), any())).thenReturn(Map.of(1L, 100.0, 3L, 30.0));

        var out = svc.floorplanLive(7L, new RangeQuery(RangeType.TODAY, null, null, null, null));

        assertThat(out.floorplan().id()).isEqualTo(7L);
        assertThat(out.points()).hasSize(2);
        var hi = out.points().stream().filter(p -> p.meterId().equals(1L)).findFirst().orElseThrow();
        assertThat(hi.value()).isEqualTo(100.0);
        assertThat(hi.level()).isEqualTo("HIGH");  // 100/100 = 1.0
        assertThat(hi.energyType()).isEqualTo("ELEC");
        var lo = out.points().stream().filter(p -> p.meterId().equals(3L)).findFirst().orElseThrow();
        assertThat(lo.level()).isEqualTo("LOW");   // 30/100 = 0.3 → LOW
    }

    @Test
    void floorplanLive_filtersOutInvisibleMeters() {
        FloorplanPointDTO visible = new FloorplanPointDTO(101L, 1L, BigDecimal.ZERO, BigDecimal.ZERO, "A");
        FloorplanPointDTO hidden = new FloorplanPointDTO(102L, 999L, BigDecimal.ZERO, BigDecimal.ZERO, "X");
        when(floorplans.getById(7L)).thenReturn(fpFixture(7L, visible, hidden));
        when(support.resolveMeters(any(), isNull())).thenReturn(List.of(M1));
        when(tsq.sumByMeter(anyCollection(), any())).thenReturn(Map.of(1L, 50.0));

        var out = svc.floorplanLive(7L, new RangeQuery(RangeType.TODAY, null, null, null, null));
        assertThat(out.points()).hasSize(1);
        assertThat(out.points().get(0).meterId()).isEqualTo(1L);
    }

    @Test
    void floorplanLive_throwsForbidden_whenAllPointsInvisible() {
        FloorplanPointDTO hidden = new FloorplanPointDTO(102L, 999L, BigDecimal.ZERO, BigDecimal.ZERO, "X");
        when(floorplans.getById(7L)).thenReturn(fpFixture(7L, hidden));
        when(support.resolveMeters(any(), isNull())).thenReturn(List.of(M1));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                svc.floorplanLive(7L, new RangeQuery(RangeType.TODAY, null, null, null, null)))
                .isInstanceOf(com.ems.core.exception.ForbiddenException.class);
    }

    @Test
    void floorplanLive_zeroValues_yieldNoneLevel() {
        FloorplanPointDTO p = new FloorplanPointDTO(101L, 1L, BigDecimal.ZERO, BigDecimal.ZERO, "A");
        when(floorplans.getById(7L)).thenReturn(fpFixture(7L, p));
        when(support.resolveMeters(any(), isNull())).thenReturn(List.of(M1));
        when(tsq.sumByMeter(anyCollection(), any())).thenReturn(Map.of());  // 全 0

        var out = svc.floorplanLive(7L, new RangeQuery(RangeType.TODAY, null, null, null, null));
        assertThat(out.points().get(0).level()).isEqualTo("NONE");
        assertThat(out.points().get(0).value()).isEqualTo(0.0);
    }

}
