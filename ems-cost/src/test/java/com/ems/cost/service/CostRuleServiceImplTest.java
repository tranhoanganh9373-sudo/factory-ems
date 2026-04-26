package com.ems.cost.service;

import com.ems.cost.dto.CostRuleDTO;
import com.ems.cost.dto.CreateCostRuleReq;
import com.ems.cost.dto.UpdateCostRuleReq;
import com.ems.cost.entity.AllocationAlgorithm;
import com.ems.cost.entity.CostAllocationRule;
import com.ems.cost.entity.EnergyTypeCode;
import com.ems.cost.repository.CostAllocationRuleRepository;
import com.ems.cost.service.impl.CostRuleServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CostRuleServiceImplTest {

    private CostAllocationRuleRepository repo;
    private CostRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(CostAllocationRuleRepository.class);
        service = new CostRuleServiceImpl(repo);
    }

    private CreateCostRuleReq directReq(String code) {
        return new CreateCostRuleReq(
                code, "rule " + code, "desc",
                EnergyTypeCode.ELEC, AllocationAlgorithm.DIRECT,
                100L, List.of(50L), Map.of(),
                100, true, LocalDate.of(2026, 1, 1), null);
    }

    @Test
    void create_persists_with_defaults_and_returns_dto() {
        when(repo.existsByCode("R1")).thenReturn(false);
        AtomicReference<CostAllocationRule> saved = new AtomicReference<>();
        when(repo.save(any(CostAllocationRule.class))).thenAnswer(inv -> {
            CostAllocationRule e = inv.getArgument(0);
            saved.set(e);
            return e;
        });

        CostRuleDTO dto = service.create(directReq("R1"));

        assertThat(dto.code()).isEqualTo("R1");
        assertThat(saved.get().getEnabled()).isTrue();
        assertThat(saved.get().getPriority()).isEqualTo(100);
        assertThat(saved.get().getTargetOrgIds()).containsExactly(50L);
    }

    @Test
    void create_throws_on_duplicate_code() {
        when(repo.existsByCode("DUP")).thenReturn(true);
        assertThatThrownBy(() -> service.create(directReq("DUP")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
        verify(repo, never()).save(any());
    }

    @Test
    void create_rejects_effectiveTo_before_effectiveFrom() {
        CreateCostRuleReq req = new CreateCostRuleReq(
                "R2", "n", null, EnergyTypeCode.ELEC, AllocationAlgorithm.DIRECT,
                100L, List.of(50L), null, 100, true,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1));
        when(repo.existsByCode("R2")).thenReturn(false);
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveTo");
    }

    @Test
    void create_composite_requires_steps() {
        CreateCostRuleReq req = new CreateCostRuleReq(
                "C1", "comp", null, EnergyTypeCode.ELEC, AllocationAlgorithm.COMPOSITE,
                100L, List.of(50L), Map.of(), 100, true, LocalDate.of(2026, 1, 1), null);
        when(repo.existsByCode("C1")).thenReturn(false);
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("steps");
    }

    @Test
    void update_partial_only_changes_provided_fields() {
        CostAllocationRule existing = new CostAllocationRule();
        existing.setCode("R3");
        existing.setName("old");
        existing.setEnergyType(EnergyTypeCode.ELEC);
        existing.setAlgorithm(AllocationAlgorithm.DIRECT);
        existing.setSourceMeterId(100L);
        existing.setTargetOrgIds(new Long[]{50L});
        existing.setPriority(100);
        existing.setEnabled(true);
        existing.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        when(repo.findById(7L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateCostRuleReq req = new UpdateCostRuleReq(
                "new name", null, null, null, null, null, null, null, false, null, null);

        CostRuleDTO dto = service.update(7L, req);

        assertThat(dto.name()).isEqualTo("new name");
        assertThat(dto.enabled()).isFalse();
        assertThat(dto.algorithm()).isEqualTo(AllocationAlgorithm.DIRECT); // unchanged
    }

    @Test
    void update_throws_if_targetOrgIds_emptied() {
        CostAllocationRule existing = new CostAllocationRule();
        existing.setEnergyType(EnergyTypeCode.ELEC);
        existing.setAlgorithm(AllocationAlgorithm.DIRECT);
        existing.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        when(repo.findById(8L)).thenReturn(Optional.of(existing));

        UpdateCostRuleReq req = new UpdateCostRuleReq(
                null, null, null, null, null, List.of(), null, null, null, null, null);

        assertThatThrownBy(() -> service.update(8L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetOrgIds");
    }

    @Test
    void delete_throws_when_missing() {
        when(repo.existsById(99L)).thenReturn(false);
        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).deleteById(any());
    }

    @Test
    void delete_succeeds_when_present() {
        when(repo.existsById(7L)).thenReturn(true);
        service.delete(7L);
        verify(repo).deleteById(7L);
    }

    @Test
    void getById_throws_when_missing() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void list_returns_all_as_dtos() {
        CostAllocationRule a = new CostAllocationRule();
        a.setCode("A");
        a.setEnergyType(EnergyTypeCode.ELEC);
        a.setAlgorithm(AllocationAlgorithm.DIRECT);
        when(repo.findAll()).thenReturn(List.of(a));
        List<CostRuleDTO> dtos = service.list();
        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).code()).isEqualTo("A");
    }
}
