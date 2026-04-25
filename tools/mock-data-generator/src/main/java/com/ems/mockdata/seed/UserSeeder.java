package com.ems.mockdata.seed;

import com.ems.auth.entity.Role;
import com.ems.auth.entity.User;
import com.ems.auth.entity.UserRole;
import com.ems.auth.repository.RoleRepository;
import com.ems.auth.repository.UserRepository;
import com.ems.auth.repository.UserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds 12 mock users: 1 admin / 2 finance / 4 manager / 5 viewer.
 * Password for all mock users: "Mock123!" (BCrypt).
 */
@Component
public class UserSeeder {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);
    private static final String PREFIX = OrgTreeSeeder.PREFIX;
    private static final String DEFAULT_PWD_HASH =
        new BCryptPasswordEncoder(12).encode("Mock123!");

    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final UserRoleRepository userRoleRepo;

    public UserSeeder(UserRepository userRepo, RoleRepository roleRepo,
                      UserRoleRepository userRoleRepo) {
        this.userRepo = userRepo;
        this.roleRepo = roleRepo;
        this.userRoleRepo = userRoleRepo;
    }

    @Transactional
    public void seed() {
        if (userRepo.existsByUsername(PREFIX + "admin")) {
            log.info("Users already seeded, skipping");
            return;
        }
        log.info("Seeding mock users...");

        ensureRole("FINANCE", "财务", "成本核算");
        ensureRole("MANAGER", "车间主任", "车间级数据");
        ensureRole("VIEWER",  "查看者",   "按节点权限查看");
        ensureRole("ADMIN",   "管理员",   "系统管理员");

        createUser(PREFIX + "admin",     "Mock管理员",    "ADMIN");
        createUser(PREFIX + "finance-1", "Mock财务-1",    "FINANCE");
        createUser(PREFIX + "finance-2", "Mock财务-2",    "FINANCE");
        createUser(PREFIX + "mgr-a",     "Mock主任-冲压", "MANAGER");
        createUser(PREFIX + "mgr-b",     "Mock主任-焊接", "MANAGER");
        createUser(PREFIX + "mgr-c",     "Mock主任-涂装", "MANAGER");
        createUser(PREFIX + "mgr-d",     "Mock主任-总装", "MANAGER");
        createUser(PREFIX + "viewer-1",  "Mock查看-1",    "VIEWER");
        createUser(PREFIX + "viewer-2",  "Mock查看-2",    "VIEWER");
        createUser(PREFIX + "viewer-3",  "Mock查看-3",    "VIEWER");
        createUser(PREFIX + "viewer-4",  "Mock查看-4",    "VIEWER");
        createUser(PREFIX + "viewer-5",  "Mock查看-5",    "VIEWER");

        log.info("Seeded 12 mock users");
    }

    private void createUser(String username, String displayName, String roleCode) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(DEFAULT_PWD_HASH);
        u.setDisplayName(displayName);
        u.setEnabled(true);
        userRepo.save(u);

        Role role = roleRepo.findByCode(roleCode)
            .orElseThrow(() -> new IllegalStateException("Role not found: " + roleCode));
        userRoleRepo.save(new UserRole(u.getId(), role.getId()));
    }

    private void ensureRole(String code, String name, String desc) {
        if (roleRepo.findByCode(code).isEmpty()) {
            Role r = new Role();
            r.setCode(code);
            r.setName(name);
            r.setDescription(desc);
            roleRepo.save(r);
        }
    }
}
