package com.ems.auth.entity;
import jakarta.persistence.*;

@Entity
@Table(name = "user_roles")
@IdClass(UserRoleId.class)
public class UserRole {
    @Id @Column(name = "user_id") private Long userId;
    @Id @Column(name = "role_id") private Long roleId;
    public UserRole() {}
    public UserRole(Long u, Long r) { this.userId = u; this.roleId = r; }
    public Long getUserId() { return userId; }
    public Long getRoleId() { return roleId; }
    public void setUserId(Long v) { this.userId = v; }
    public void setRoleId(Long v) { this.roleId = v; }
}
