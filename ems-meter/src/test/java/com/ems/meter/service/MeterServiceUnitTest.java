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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeterServiceUnitTest {

    private static final String GLOBAL_MEASUREMENT = "energy_reading";

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
        svc = new MeterServiceImpl(meters, topology, energyTypes, orgNodes, GLOBAL_MEASUREMENT);
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
                "M1", "电表 1", 1L, 10L, true, null, null, null, null, null, null)))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void create_unknownEnergyType_throwsNotFound() {
        when(meters.existsByCode(anyString())).thenReturn(false);
        when(energyTypes.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.create(new CreateMeterReq(
                "M1", "x", 99L, 10L, true, null, null, null, null, null, null)))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_unknownOrgNode_throwsNotFound() {
        when(meters.existsByCode(anyString())).thenReturn(false);
        when(energyTypes.findById(1L)).thenReturn(Optional.of(elec()));
        when(orgNodes.existsById(99L)).thenReturn(false);
        assertThatThrownBy(() -> svc.create(new CreateMeterReq(
                "M1", "x", 1L, 99L, true, null, null, null, null, null, null)))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_happyPath_savesAndForcesInfluxConventions() {
        when(meters.existsByCode("M1")).thenReturn(false);
        when(energyTypes.findById(1L)).thenReturn(Optional.of(elec()));
        when(orgNodes.existsById(10L)).thenReturn(true);
        org.mockito.ArgumentCaptor<Meter> captor = org.mockito.ArgumentCaptor.forClass(Meter.class);
        doAnswer(inv -> { ((Meter) inv.getArgument(0)).setId(42L); return inv.getArgument(0); })
            .when(meters).save(captor.capture());

        var dto = svc.create(new CreateMeterReq(
            "M1", "电表 1", 1L, 10L, null, null, null, null, null, null, null));

        assertThat(dto.id()).isEqualTo(42L);
        assertThat(dto.energyTypeCode()).isEqualTo("ELEC");
        assertThat(dto.enabled()).isTrue();
        // 强制三字段约定
        Meter saved = captor.getValue();
        assertThat(saved.getInfluxMeasurement()).isEqualTo(GLOBAL_MEASUREMENT);
        assertThat(saved.getInfluxTagKey()).isEqualTo("meter_code");
        assertThat(saved.getInfluxTagValue()).isEqualTo("M1");
        assertThat(saved.getChannelId()).isNull();
        assertThat(saved.getChannelPointKey()).isNull();
    }

    @Test
    void create_withChannelPair_persistsBoth() {
        when(meters.existsByCode("M1")).thenReturn(false);
        when(energyTypes.findById(1L)).thenReturn(Optional.of(elec()));
        when(orgNodes.existsById(10L)).thenReturn(true);
        org.mockito.ArgumentCaptor<Meter> captor = org.mockito.ArgumentCaptor.forClass(Meter.class);
        doAnswer(inv -> { ((Meter) inv.getArgument(0)).setId(42L); return inv.getArgument(0); })
            .when(meters).save(captor.capture());

        var dto = svc.create(new CreateMeterReq(
            "M1", "电表 1", 1L, 10L, true, 7L, "v1.power", null, null, null, null));

        assertThat(captor.getValue().getChannelId()).isEqualTo(7L);
        assertThat(captor.getValue().getChannelPointKey()).isEqualTo("v1.power");
        assertThat(dto.channelId()).isEqualTo(7L);
        assertThat(dto.channelPointKey()).isEqualTo("v1.power");
    }

    @Test
    void create_channelIdWithoutPointKey_throws() {
        when(meters.existsByCode("M1")).thenReturn(false);
        when(energyTypes.findById(1L)).thenReturn(Optional.of(elec()));
        when(orgNodes.existsById(10L)).thenReturn(true);
        assertThatThrownBy(() -> svc.create(new CreateMeterReq(
                "M1", "x", 1L, 10L, true, 7L, null, null, null, null, null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("channelPointKey");
        verify(meters, never()).save(any());
    }

    @Test
    void create_pointKeyWithoutChannelId_throws() {
        when(meters.existsByCode("M1")).thenReturn(false);
        when(energyTypes.findById(1L)).thenReturn(Optional.of(elec()));
        when(orgNodes.existsById(10L)).thenReturn(true);
        assertThatThrownBy(() -> svc.create(new CreateMeterReq(
                "M1", "x", 1L, 10L, true, null, "v1.power", null, null, null, null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("channelPointKey");
        verify(meters, never()).save(any());
    }

    @Test
    void create_blankPointKey_normalizedToNull() {
        when(meters.existsByCode("M1")).thenReturn(false);
        when(energyTypes.findById(1L)).thenReturn(Optional.of(elec()));
        when(orgNodes.existsById(10L)).thenReturn(true);
        org.mockito.ArgumentCaptor<Meter> captor = org.mockito.ArgumentCaptor.forClass(Meter.class);
        doAnswer(inv -> { ((Meter) inv.getArgument(0)).setId(42L); return inv.getArgument(0); })
            .when(meters).save(captor.capture());

        // channelId=null + channelPointKey="  " 应被归一化为 (null, null)，不抛错
        svc.create(new CreateMeterReq("M1", "x", 1L, 10L, true, null, "  ", null, null, null, null));

        assertThat(captor.getValue().getChannelId()).isNull();
        assertThat(captor.getValue().getChannelPointKey()).isNull();
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
    void update_changeCodeToOccupied_throws() {
        Meter m = new Meter();
        m.setId(1L); m.setCode("OLD");
        when(meters.findById(1L)).thenReturn(Optional.of(m));
        when(meters.existsByCode("NEW")).thenReturn(true);
        assertThatThrownBy(() -> svc.update(1L, new UpdateMeterReq(
                "NEW", "x", 1L, 10L, true, null, null, null, null, null, null)))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void update_renormalizesInfluxFields() {
        // 旧脏数据：tagKey="meter"、measurement="energy"，应被规范化为 ("energy_reading", "meter_code", code)
        Meter m = new Meter();
        m.setId(1L); m.setCode("M1");
        m.setInfluxMeasurement("energy"); m.setInfluxTagKey("meter"); m.setInfluxTagValue("M1");
        when(meters.findById(1L)).thenReturn(Optional.of(m));
        when(energyTypes.findById(1L)).thenReturn(Optional.of(elec()));
        when(orgNodes.existsById(10L)).thenReturn(true);
        when(topology.findByChildMeterId(1L)).thenReturn(Optional.empty());

        svc.update(1L, new UpdateMeterReq("M1", "x", 1L, 10L, true, null, null, null, null, null, null));

        assertThat(m.getInfluxMeasurement()).isEqualTo(GLOBAL_MEASUREMENT);
        assertThat(m.getInfluxTagKey()).isEqualTo("meter_code");
        assertThat(m.getInfluxTagValue()).isEqualTo("M1");
    }

    @Test
    void update_changeChannelPointKey_persists() {
        Meter m = new Meter();
        m.setId(1L); m.setCode("M1"); m.setChannelId(7L); m.setChannelPointKey("old.key");
        when(meters.findById(1L)).thenReturn(Optional.of(m));
        when(energyTypes.findById(1L)).thenReturn(Optional.of(elec()));
        when(orgNodes.existsById(10L)).thenReturn(true);
        when(topology.findByChildMeterId(1L)).thenReturn(Optional.empty());

        svc.update(1L, new UpdateMeterReq("M1", "x", 1L, 10L, true, 7L, "new.key", null, null, null, null));

        assertThat(m.getChannelPointKey()).isEqualTo("new.key");
    }

    @Test
    void create_duplicateChannelPointKey_throwsConflict() {
        when(meters.existsByCode("M2")).thenReturn(false);
        when(energyTypes.findById(1L)).thenReturn(Optional.of(elec()));
        when(orgNodes.existsById(10L)).thenReturn(true);
        Meter occupant = new Meter(); occupant.setId(99L);
        when(meters.findByChannelIdAndChannelPointKey(7L, "shared.key"))
            .thenReturn(Optional.of(occupant));

        assertThatThrownBy(() -> svc.create(new CreateMeterReq(
                "M2", "x", 1L, 10L, true, 7L, "shared.key", null, null, null, null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("shared.key");
        verify(meters, never()).save(any());
    }

    @Test
    void update_duplicateChannelPointKey_throwsConflict() {
        Meter self = new Meter();
        self.setId(1L); self.setCode("M1"); self.setChannelId(7L); self.setChannelPointKey("old.key");
        Meter other = new Meter(); other.setId(2L);
        when(meters.findById(1L)).thenReturn(Optional.of(self));
        when(energyTypes.findById(1L)).thenReturn(Optional.of(elec()));
        when(orgNodes.existsById(10L)).thenReturn(true);
        when(topology.findByChildMeterId(1L)).thenReturn(Optional.empty());
        when(meters.findByChannelIdAndChannelPointKey(7L, "shared.key"))
            .thenReturn(Optional.of(other));

        assertThatThrownBy(() -> svc.update(1L, new UpdateMeterReq(
                "M1", "x", 1L, 10L, true, 7L, "shared.key", null, null, null, null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("shared.key");
    }

    @Test
    void update_keepingSameChannelPointKey_doesNotThrow() {
        // 编辑时保留原 channelPointKey，自己映射回自己不应被当作冲突
        Meter self = new Meter();
        self.setId(1L); self.setCode("M1"); self.setChannelId(7L); self.setChannelPointKey("my.key");
        when(meters.findById(1L)).thenReturn(Optional.of(self));
        when(energyTypes.findById(1L)).thenReturn(Optional.of(elec()));
        when(orgNodes.existsById(10L)).thenReturn(true);
        when(topology.findByChildMeterId(1L)).thenReturn(Optional.empty());
        when(meters.findByChannelIdAndChannelPointKey(7L, "my.key"))
            .thenReturn(Optional.of(self));

        svc.update(1L, new UpdateMeterReq("M1", "x", 1L, 10L, true, 7L, "my.key", null, null, null, null));

        assertThat(self.getChannelPointKey()).isEqualTo("my.key");
    }

    @Test
    void update_inconsistentChannelPair_throws() {
        Meter m = new Meter(); m.setId(1L); m.setCode("M1");
        when(meters.findById(1L)).thenReturn(Optional.of(m));
        assertThatThrownBy(() -> svc.update(1L, new UpdateMeterReq(
                "M1", "x", 1L, 10L, true, 7L, null, null, null, null, null)))
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
        assertThat(out).hasSize(1);
        assertThat(out.get(0).id()).isEqualTo(1L);
    }
}
