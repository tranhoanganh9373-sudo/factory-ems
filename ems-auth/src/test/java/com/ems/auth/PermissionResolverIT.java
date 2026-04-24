package com.ems.auth;

import com.ems.auth.dto.*;
import com.ems.auth.service.*;
import com.ems.orgtree.dto.CreateOrgNodeReq;
import com.ems.orgtree.service.OrgNodeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AuthITApp.class)
@ActiveProfiles("test")
@Testcontainers
class PermissionResolverIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired OrgNodeService tree;
    @Autowired UserService users;
    @Autowired NodePermissionService perms;
    @Autowired PermissionResolver resolver;

    @Test
    void viewer_withSubtreePerm_seesOnlyThatSubtree() {
        var a = tree.create(new CreateOrgNodeReq(null,   "A", "PA", "PLANT", 0));
        var b = tree.create(new CreateOrgNodeReq(a.id(), "B", "PB", "WS",    0));
        var c = tree.create(new CreateOrgNodeReq(b.id(), "C", "PC", "DEV",   0));
        var x = tree.create(new CreateOrgNodeReq(null,   "X", "PX", "PLANT", 0));

        var u = users.create(new CreateUserReq("zhang3", "password123", "张三", List.of("VIEWER")));
        perms.assign(u.id(), new AssignNodePermissionReq(b.id(), "SUBTREE"));

        var visible = resolver.visibleNodeIds(u.id());
        assertThat(visible).containsExactlyInAnyOrder(b.id(), c.id());
        assertThat(visible).doesNotContain(a.id(), x.id());
    }

    @Test
    void admin_getsAllMarker() {
        var u = users.create(new CreateUserReq("adminx", "password123", "A", List.of("ADMIN")));
        assertThat(resolver.hasAllNodes(resolver.visibleNodeIds(u.id()))).isTrue();
    }

    @Test
    void nodeOnlyScope_excludesDescendants() {
        var p = tree.create(new CreateOrgNodeReq(null,   "P", "PP", "PLANT", 0));
        var q = tree.create(new CreateOrgNodeReq(p.id(), "Q", "PQ", "WS",    0));
        var u = users.create(new CreateUserReq("li4", "password123", "李四", List.of("VIEWER")));
        perms.assign(u.id(), new AssignNodePermissionReq(p.id(), "NODE_ONLY"));

        var visible = resolver.visibleNodeIds(u.id());
        assertThat(visible).contains(p.id()).doesNotContain(q.id());
    }
}
