package com.ems.meter.service;

import com.ems.core.exception.BusinessException;
import com.ems.core.exception.NotFoundException;
import com.ems.meter.dto.CreateMeterReq;
import com.ems.meter.dto.UpdateMeterReq;
import com.ems.meter.entity.EnergyType;
import com.ems.meter.entity.Meter;
import com.ems.meter.entity.MeterTopology;
import com.ems.meter.repository.EnergyTypeRepository;
import com.ems.meter.repository.MeterRepository;
import com.ems.meter.repository.MeterTopologyRepository;
import com.ems.meter.service.impl.MeterServiceImpl;
import com.ems.orgtree.repository.OrgNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeterServiceUnitTest {

    MeterRepository meters;
    MeterTopologyRepository topology;
    EnergyTypeRepository energyTypes;
    OrgNodeRepository orgNodes;
    MeterServiceImpl svc;

    @BeforeEach
    void setup() {
        meters = mock(MeterRepository.class);
        topology = mock(MeterTopologyRepository.class);
        energyTypes = mock(EnergyTypeRepository.class);
        orgNodes = mock(OrgNodeRepository.class);
        svc = new MeterServiceImpl(meters, topology, energyTypes, orgNodes);
    }

    private EnergyType elec() {
        EnergyType t = new EnergyType();
        // EnergyType has no setters; reflective hack for tests.
        try {
            var f = EnergyType.class.getDeclaredField("id"); f.setAccessible(true); f.set(t, 1L);
            f = EnergyType.class.getDeclaredField("code"); f.setAccessible(true); f.set(t, "ELEC");
            f = EnergyType.class.getDeclaredField("name"); f.setAccessible(true); f.set(t, "电");
            f = EnergyType.class.getDeclaredField("unit"); f.setAccessible(true); f.set(t, "kWh");
        } catch (Exception e) { throw new RuntimeException(e); }
        return t;
    }

    @Test
    void create_duplicateCode_throwsConflict() {
        when(meters.existsByCode("M1")).thenReturn(true);
        assertThatThrownBy(() -> svc.create(new CreateMeterReq(
                "M1", "电表 1", 1L, 10L, "energy", "meter", "M1", true)))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void create_duplicateInfluxTriple_throwsConflict() {
        when(meters.existsByCode("M1")).thenReturn(false);
        when(meters.existsByInfluxMeasurementAndInfluxTagKeyAndInfluxTagValue(
                "energy", "meter", "M1")).thenReturn(true);
        assertThatThrownBy(() -> svc.create(new CreateMeterReq(
                "M1", "电表 1", 1L, 10L, "energy", "meter", "M1", true)))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void create_unknownEnergyType_throwsNotFound() {
        when(meters.existsByCode(anyString())).thenReturn(false);
        when(meters.existsByInfluxMeasurementAndInfluxTagKeyAndInfluxTagValue(any(), any(), any()))
            .thenReturn(false);
        when(energyTypes.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.create(new CreateMeterReq(
                "M1", "x", 99L, 10L, "energy", "meter", "M1", true)))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_unknownOrgNode_throwsNotFound() {
        when(meters.existsByCode(anyString())).thenReturn(false);
        when(meters.existsByInfluxMeasurementAndInfluxTagKeyAndInfluxTagValue(any(), any(), any()))
            .thenReturn(false);
        when(energyTypes.findById(1L)).thenReturn(Optional.of(elec()));
        when(orgNodes.existsById(99L)).thenReturn(false);
        assertThatThrownBy(() -> svc.create(new CreateMeterReq(
                "M1", "x", 1L, 99L, "energy", "meter", "M1", true)))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_happyPath_savesAndReturnsDTO() {
        when(meters.existsByCode("M1")).thenReturn(false);
        when(meters.existsByInfluxMeasurementAndInfluxTagKeyAndInfluxTagValue(any(), any(), any()))
            .thenReturn(false);
        when(energyTypes.findById(1L)).thenReturn(Optional.of(elec()));
        when(orgNodes.existsById(10L)).thenReturn(true);
        doAnswer(inv -> { ((Meter) inv.getArgument(0)).setId(42L); return inv.getArgument(0); })
            .when(meters).save(any());

        var dto = svc.create(new CreateMeterReq(
            "M1", "电表 1", 1L, 10L, "energy", "meter", "M1", null));

        assert dto.id() == 42L;
        assert "ELEC".equals(dto.energyTypeCode());
        assert dto.enabled();
    }

    @Test
    void delete_withChildren_throws() {
        Meter m = new Meter(); m.setId(1L);
        when(meters.findById(1L)).thenReturn(Optional.of(m));
        when(topology.countByParentMeterId(1L)).thenReturn(2L);
        assertThatThrownBy(() -> svc.delete(1L)).isInstanceOf(BusinessException.class);
        verify(meters, never()).delete(any());
    }

    @Test
    void delete_leaf_unbindsAndDeletes() {
        Meter m = new Meter(); m.setId(1L);
        MeterTopology t = new MeterTopology(); t.setChildMeterId(1L); t.setParentMeterId(2L);
        when(meters.findById(1L)).thenReturn(Optional.of(m));
        when(topology.countByParentMeterId(1L)).thenReturn(0L);
        when(topology.findByChildMeterId(1L)).thenReturn(Optional.of(t));
        svc.delete(1L);
        verify(topology).delete(t);
        verify(meters).delete(m);
    }

    @Test
    void update_changeTagToOccupiedTriple_throws() {
        Meter m = new Meter();
        m.setId(1L); m.setCode("M1");
        m.setInfluxMeasurement("a"); m.setInfluxTagKey("k"); m.setInfluxTagValue("v");
        when(meters.findById(1L)).thenReturn(Optional.of(m));
        when(meters.existsByInfluxMeasurementAndInfluxTagKeyAndInfluxTagValue("b", "k", "v"))
            .thenReturn(true);
        assertThatThrownBy(() -> svc.update(1L, new UpdateMeterReq(
                "x", 1L, 10L, "b", "k", "v", true)))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void list_filtersByOrgNodeAndEnergyType() {
        Meter a = new Meter(); a.setId(1L); a.setCode("A");
        a.setEnergyTypeId(1L); a.setOrgNodeId(10L); a.setEnabled(true);
        Meter b = new Meter(); b.setId(2L); b.setCode("B");
        b.setEnergyTypeId(2L); b.setOrgNodeId(10L); b.setEnabled(true);
        Meter c = new Meter(); c.setId(3L); c.setCode("C");
        c.setEnergyTypeId(1L); c.setOrgNodeId(20L); c.setEnabled(true);
        when(meters.findAllByOrderByCodeAsc()).thenReturn(List.of(a, b, c));
        when(energyTypes.findAll()).thenReturn(List.of(elec()));
        when(topology.findAll()).thenReturn(List.of());

        var out = svc.list(10L, 1L, null);
        assert out.size() == 1;
        assert out.get(0).id() == 1L;
    }
}
