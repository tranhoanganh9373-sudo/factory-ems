package com.ems.orgtree;

import com.ems.audit.aspect.AuditContext;
import com.ems.core.security.PermissionResolver;
import com.ems.orgtree.dto.*;
import com.ems.orgtree.repository.*;
import com.ems.orgtree.service.*;
import com.ems.orgtree.service.impl.OrgNodeServiceImpl;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = ClosureConsistencyIT.TestApp.class)
@Testcontainers
@ActiveProfiles("test")
class ClosureConsistencyIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired OrgNodeService svc;
    @Autowired OrgNodeClosureRepository closure;

    @Test
    void tree_A_B_C_closureHasCorrectDepths() {
        var a = svc.create(new CreateOrgNodeReq(null,   "工厂A", "A", "PLANT", 0));
        var b = svc.create(new CreateOrgNodeReq(a.id(), "车间B", "B", "WS",    0));
        var c = svc.create(new CreateOrgNodeReq(b.id(), "设备C", "C", "DEV",   0));

        // A 的后代：A,B,C 三条
        assertThat(closure.findDescendantIds(a.id())).containsExactlyInAnyOrder(a.id(), b.id(), c.id());
        // C 的祖先：A,B,C 三条
        assertThat(closure.findAncestorIds(c.id())).containsExactlyInAnyOrder(a.id(), b.id(), c.id());
    }

    @Test
    void moveC_fromB_toA_updatesClosure() {
        var a = svc.create(new CreateOrgNodeReq(null,   "A2", "A2", "PLANT", 0));
        var b = svc.create(new CreateOrgNodeReq(a.id(), "B2", "B2", "WS",    0));
        var c = svc.create(new CreateOrgNodeReq(b.id(), "C2", "C2", "DEV",   0));

        svc.move(c.id(), new MoveOrgNodeReq(a.id()));

        assertThat(closure.findAncestorIds(c.id())).containsExactlyInAnyOrder(a.id(), c.id());
        assertThat(closure.findDescendantIds(b.id())).containsExactly(b.id());
    }

    @Test
    void move_toOwnDescendant_throws() {
        var a = svc.create(new CreateOrgNodeReq(null,   "A3", "A3", "PLANT", 0));
        var b = svc.create(new CreateOrgNodeReq(a.id(), "B3", "B3", "WS",    0));
        var c = svc.create(new CreateOrgNodeReq(b.id(), "C3", "C3", "DEV",   0));

        assertThatThrownBy(() -> svc.move(a.id(), new MoveOrgNodeReq(c.id())))
            .isInstanceOf(com.ems.core.exception.BusinessException.class)
            .hasMessageContaining("后代");
    }

    @Test
    void delete_leaf_succeeds() {
        var a = svc.create(new CreateOrgNodeReq(null,   "D1", "D1", "PLANT", 0));
        var b = svc.create(new CreateOrgNodeReq(a.id(), "D2", "D2", "WS",    0));
        svc.delete(b.id());
        assertThat(closure.findDescendantIds(a.id())).containsExactly(a.id());
    }

    @Test
    void delete_nonLeaf_throws() {
        var a = svc.create(new CreateOrgNodeReq(null,   "D3", "D3", "PLANT", 0));
        var b = svc.create(new CreateOrgNodeReq(a.id(), "D4", "D4", "WS",    0));
        assertThatThrownBy(() -> svc.delete(a.id()))
            .isInstanceOf(com.ems.core.exception.BusinessException.class);
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackages = {"com.ems.orgtree.entity"})
    @EnableJpaRepositories(basePackages = {"com.ems.orgtree.repository"})
    @ComponentScan(basePackages = {"com.ems.orgtree.service"})
    static class TestApp {
        @Bean AuditContext ctx() {
            return new AuditContext() {
                public Long currentUserId() { return 1L; }
                public String currentUsername() { return "tester"; }
                public String currentIp() { return "127.0.0.1"; }
                public String currentUserAgent() { return "it"; }
            };
        }

        @Bean PermissionResolver permissions() {
            // 测试视角：模拟超管（visibleNodeIds 命中 ALL_NODE_IDS_MARKER），跳过权限过滤。
            return new PermissionResolver() {
                @Override public Set<Long> visibleNodeIds(Long userId) { return ALL_NODE_IDS_MARKER; }
                @Override public boolean canAccess(Long userId, Long orgNodeId) { return true; }
                @Override public boolean hasAllNodes(Set<Long> v) { return v == ALL_NODE_IDS_MARKER; }
                @Override public Long currentUserId() { return 1L; }
            };
        }
    }
}
