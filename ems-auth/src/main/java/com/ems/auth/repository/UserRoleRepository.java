package com.ems.auth.repository;
import com.ems.auth.entity.UserRole;
import com.ems.auth.entity.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {
    List<UserRole> findByUserId(Long userId);
    void deleteByUserId(Long userId);

    @Query("SELECT r.code FROM Role r JOIN UserRole ur ON ur.roleId = r.id WHERE ur.userId = :userId")
    List<String> findRoleCodesByUserId(@Param("userId") Long userId);
}
