package com.ems.orgtree.repository;

import com.ems.orgtree.entity.OrgNode;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface OrgNodeRepository extends JpaRepository<OrgNode, Long> {
    Optional<OrgNode> findByCode(String code);
    boolean existsByCode(String code);
    List<OrgNode> findAllByOrderBySortOrderAscIdAsc();
}
