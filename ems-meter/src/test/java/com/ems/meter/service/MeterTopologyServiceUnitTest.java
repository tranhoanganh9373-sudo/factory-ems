package com.ems.meter.service;

import com.ems.core.exception.BusinessException;
import com.ems.core.exception.NotFoundException;
import com.ems.meter.dto.BindParentMeterReq;
import com.ems.meter.entity.MeterTopology;
import com.ems.meter.repository.MeterRepository;
import com.ems.meter.repository.MeterTopologyRepository;
import com.ems.meter.service.impl.MeterTopologyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MeterTopologyServiceUnitTest {

    MeterRepository meters;
    MeterTopologyRepository topology;
    MeterTopologyServiceImpl svc;

    @BeforeEach
    void setup() {
        meters = mock(MeterRepository.class);
        topology = mock(MeterTopologyRepository.class);
        svc = new MeterTopologyServiceImpl(topology, meters);
    }

    @Test
    void bind_self_throws() {
        assertThatThrownBy(() -> svc.bind(1L, new BindParentMeterReq(1L)))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void bind_unknownChild_throwsNotFound() {
        when(meters.existsById(1L)).thenReturn(false);
        assertThatThrownBy(() -> svc.bind(1L, new BindParentMeterReq(2L)))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void bind_unknownParent_throwsNotFound() {
        when(meters.existsById(1L)).thenReturn(true);
        when(meters.existsById(2L)).thenReturn(false);
        assertThatThrownBy(() -> svc.bind(1L, new BindParentMeterReq(2L)))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void bind_wouldFormCycle_throws() {
        // existing edge: 2 -> 1 (1 is parent of 2). Now bind 1's parent to 2 — cycle.
        MeterTopology e = new MeterTopology(); e.setChildMeterId(2L); e.setParentMeterId(1L);
        when(meters.existsById(any())).thenReturn(true);
        when(topology.findAll()).thenReturn(List.of(e));
        assertThatThrownBy(() -> svc.bind(1L, new BindParentMeterReq(2L)))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void bind_happyPath_savesEdge() {
        when(meters.existsById(any())).thenReturn(true);
        when(topology.findAll()).thenReturn(List.of());
        when(topology.findByChildMeterId(1L)).thenReturn(Optional.empty());
        svc.bind(1L, new BindParentMeterReq(2L));
        verify(topology).save(any());
    }

    @Test
    void bind_existingEdge_updatesInPlace() {
        MeterTopology old = new MeterTopology(); old.setChildMeterId(1L); old.setParentMeterId(3L);
        when(meters.existsById(any())).thenReturn(true);
        when(topology.findAll()).thenReturn(List.of(old));
        when(topology.findByChildMeterId(1L)).thenReturn(Optional.of(old));
        svc.bind(1L, new BindParentMeterReq(2L));
        verify(topology).save(old);
        assert old.getParentMeterId() == 2L;
    }

    @Test
    void unbind_removesIfPresent() {
        MeterTopology old = new MeterTopology(); old.setChildMeterId(1L); old.setParentMeterId(3L);
        when(topology.findByChildMeterId(1L)).thenReturn(Optional.of(old));
        svc.unbind(1L);
        verify(topology).delete(old);
    }
}
