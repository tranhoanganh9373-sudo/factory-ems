package com.ems.orgtree.entity;

import java.io.Serializable;
import java.util.Objects;

public class OrgNodeClosureId implements Serializable {
    private Long ancestorId;
    private Long descendantId;

    public OrgNodeClosureId() {}
    public OrgNodeClosureId(Long a, Long d) { this.ancestorId = a; this.descendantId = d; }

    public Long getAncestorId() { return ancestorId; }
    public Long getDescendantId() { return descendantId; }
    public void setAncestorId(Long v) { this.ancestorId = v; }
    public void setDescendantId(Long v) { this.descendantId = v; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrgNodeClosureId x)) return false;
        return Objects.equals(ancestorId, x.ancestorId) && Objects.equals(descendantId, x.descendantId);
    }
    @Override public int hashCode() { return Objects.hash(ancestorId, descendantId); }
}
