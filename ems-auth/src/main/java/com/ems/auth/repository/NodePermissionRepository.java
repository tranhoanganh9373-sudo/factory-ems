package com.ems.auth.repository;
import com.ems.auth.entity.NodePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Set;
public interface NodePermissionRepository extends JpaRepository<NodePermission, Long> {
    List<NodePermission> findByUserId(Long userId);
    void deleteByUserId(Long userId);

    /** 可见节点 id 集合：SUBTREE 范围取闭包后代，NODE_ONLY 取节点本身 */
    @Query(value = """
        SELECT DISTINCT c.descendant_id
        FROM node_permissions np
        JOIN org_node_closure c ON c.ancestor_id = np.org_node_id
        WHERE np.user_id = :userId
          AND (np.scope = 'SUBTREE' OR c.depth = 0)
    """, nativeQuery = true)
    Set<Long> findVisibleNodeIds(@Param("userId") Long userId);
}
