package com.ems.orgtree.service;

import com.ems.core.exception.BusinessException;
import com.ems.orgtree.dto.CreateOrgNodeReq;
import com.ems.orgtree.entity.OrgNode;
import com.ems.orgtree.repository.OrgNodeClosureRepository;
import com.ems.orgtree.repository.OrgNodeRepository;
import com.ems.orgtree.service.impl.OrgNodeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgNodeServiceUnitTest {

    OrgNodeRepository nodes;
    OrgNodeClosureRepository closure;
    OrgNodeServiceImpl svc;

    @BeforeEach void setup() {
        nodes = mock(OrgNodeRepository.class);
        closure = mock(OrgNodeClosureRepository.class);
        svc = new OrgNodeServiceImpl(nodes, closure);
    }

    @Test void create_root_insertsSelfClosure() {
        when(nodes.existsByCode("A")).thenReturn(false);
        doAnswer(inv -> { ((OrgNode) inv.getArgument(0)).setId(10L); return inv.getArgument(0); })
            .when(nodes).save(any());

        svc.create(new CreateOrgNodeReq(null, "工厂A", "A", "PLANT", 0));

        verify(closure).insertForRoot(10L);
        verify(closure, never()).insertForNewLeaf(anyLong(), anyLong());
    }

    @Test void create_duplicateCode_throws() {
        when(nodes.existsByCode("A")).thenReturn(true);
        assertThatThrownBy(() ->
            svc.create(new CreateOrgNodeReq(null, "x", "A", "PLANT", 0)))
            .isInstanceOf(BusinessException.class);
    }

    @Test void create_leaf_insertsClosureWithParent() {
        when(nodes.existsByCode("L")).thenReturn(false);
        when(nodes.findById(1L)).thenReturn(Optional.of(new OrgNode()));
        doAnswer(inv -> { ((OrgNode) inv.getArgument(0)).setId(20L); return inv.getArgument(0); })
            .when(nodes).save(any());

        svc.create(new CreateOrgNodeReq(1L, "leaf", "L", "DEVICE", 0));

        verify(closure).insertForNewLeaf(20L, 1L);
    }
}
