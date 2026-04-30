CREATE TABLE org_nodes (
    id           BIGSERIAL PRIMARY KEY,
    parent_id    BIGINT REFERENCES org_nodes(id) ON DELETE RESTRICT,
    name         VARCHAR(128) NOT NULL,
    code         VARCHAR(64)  NOT NULL UNIQUE,
    node_type    VARCHAR(32)  NOT NULL,
    sort_order   INT          NOT NULL DEFAULT 0,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_org_nodes_parent ON org_nodes(parent_id);

CREATE TABLE org_node_closure (
    ancestor_id   BIGINT NOT NULL REFERENCES org_nodes(id) ON DELETE CASCADE,
    descendant_id BIGINT NOT NULL REFERENCES org_nodes(id) ON DELETE CASCADE,
    depth         INT    NOT NULL,
    PRIMARY KEY (ancestor_id, descendant_id)
);

CREATE INDEX idx_closure_descendant ON org_node_closure(descendant_id);
CREATE INDEX idx_closure_ancestor   ON org_node_closure(ancestor_id, depth);
