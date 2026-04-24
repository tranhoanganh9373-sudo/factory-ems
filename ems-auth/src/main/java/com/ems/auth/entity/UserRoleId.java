package com.ems.auth.entity;
import java.io.Serializable;
import java.util.Objects;
public class UserRoleId implements Serializable {
    private Long userId;
    private Long roleId;
    public UserRoleId() {}
    public UserRoleId(Long u, Long r) { this.userId = u; this.roleId = r; }
    public Long getUserId() { return userId; }
    public Long getRoleId() { return roleId; }
    public void setUserId(Long v) { this.userId = v; }
    public void setRoleId(Long v) { this.roleId = v; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserRoleId x)) return false;
        return Objects.equals(userId, x.userId) && Objects.equals(roleId, x.roleId);
    }
    @Override public int hashCode() { return Objects.hash(userId, roleId); }
}
