package com.ems.orgtree.repository;

import com.ems.orgtree.entity.OrgNodeClosure;
import com.ems.orgtree.entity.OrgNodeClosureId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrgNodeClosureRepository extends JpaRepository<OrgNodeClosure, OrgNodeClosureId> {

    /** 返回 nodeId 及其所有后代 id（含自身） */
    @Query(value = """
        SELECT descendant_id FROM org_node_closure WHERE ancestor_id = :nodeId
    """, nativeQuery = true)
    List<Long> findDescendantIds(@Param("nodeId") Long nodeId);

    /** nodeId 的所有祖先 id（含自身） */
    @Query(value = """
        SELECT ancestor_id FROM org_node_closure WHERE descendant_id = :nodeId
    """, nativeQuery = true)
    List<Long> findAncestorIds(@Param("nodeId") Long nodeId);

    /** 插入：newNode 与 parent 所有祖先的新闭包行（depth = 祖先到 parent 的深度 + 1），以及 newNode 自身的 (self,self,0) */
    @Modifying
    @Query(value = """
        INSERT INTO org_node_closure (ancestor_id, descendant_id, depth)
        SELECT ancestor_id, :nodeId, depth + 1 FROM org_node_closure WHERE descendant_id = :parentId
        UNION ALL SELECT :nodeId, :nodeId, 0
    """, nativeQuery = true)
    void insertForNewLeaf(@Param("nodeId") Long nodeId, @Param("parentId") Long parentId);

    /** 根节点（没有父）：只插自身 */
    @Modifying
    @Query(value = """
        INSERT INTO org_node_closure (ancestor_id, descendant_id, depth) VALUES (:nodeId, :nodeId, 0)
    """, nativeQuery = true)
    void insertForRoot(@Param("nodeId") Long nodeId);

    /** 移动子树 move：删除旧祖先对子树的闭包行 */
    @Modifying
    @Query(value = """
        DELETE FROM org_node_closure
        WHERE descendant_id IN (
            SELECT descendant_id FROM org_node_closure WHERE ancestor_id = :nodeId
        )
          AND ancestor_id IN (
            SELECT ancestor_id FROM org_node_closure
            WHERE descendant_id = :nodeId AND ancestor_id <> :nodeId
          )
    """, nativeQuery = true)
    void deleteAncestorsOfSubtree(@Param("nodeId") Long nodeId);

    /** 移动子树 move：插入新祖先对子树的闭包行 */
    @Modifying
    @Query(value = """
        INSERT INTO org_node_closure (ancestor_id, descendant_id, depth)
        SELECT super_tree.ancestor_id,
               sub_tree.descendant_id,
               super_tree.depth + sub_tree.depth + 1
        FROM org_node_closure super_tree
        CROSS JOIN org_node_closure sub_tree
        WHERE super_tree.descendant_id = :newParentId
          AND sub_tree.ancestor_id    = :nodeId
    """, nativeQuery = true)
    void insertAncestorsForSubtree(@Param("nodeId") Long nodeId, @Param("newParentId") Long newParentId);

    /** 检查 newParent 是不是 node 的后代（防环） */
    @Query(value = """
        SELECT COUNT(*) > 0 FROM org_node_closure
        WHERE ancestor_id = :nodeId AND descendant_id = :newParentId
    """, nativeQuery = true)
    boolean isDescendant(@Param("nodeId") Long nodeId, @Param("newParentId") Long newParentId);
}
