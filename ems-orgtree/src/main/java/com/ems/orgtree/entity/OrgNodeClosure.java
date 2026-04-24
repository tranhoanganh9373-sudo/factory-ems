package com.ems.orgtree.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "org_node_closure")
@IdClass(OrgNodeClosureId.class)
public class OrgNodeClosure {

    @Id @Column(name = "ancestor_id")   private Long ancestorId;
    @Id @Column(name = "descendant_id") private Long descendantId;
    @Column(nullable = false)           private Integer depth;

    public OrgNodeClosure() {}
    public OrgNodeClosure(Long ancestorId, Long descendantId, Integer depth) {
        this.ancestorId = ancestorId; this.descendantId = descendantId; this.depth = depth;
    }

    public Long getAncestorId() { return ancestorId; }
    public Long getDescendantId() { return descendantId; }
    public Integer getDepth() { return depth; }
    public void setAncestorId(Long v) { this.ancestorId = v; }
    public void setDescendantId(Long v) { this.descendantId = v; }
    public void setDepth(Integer v) { this.depth = v; }
}
