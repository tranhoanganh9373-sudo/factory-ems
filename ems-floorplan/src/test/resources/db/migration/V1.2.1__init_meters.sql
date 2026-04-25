CREATE TABLE meters (
    id                   BIGSERIAL    PRIMARY KEY,
    code                 VARCHAR(64)  NOT NULL UNIQUE,
    name                 VARCHAR(128) NOT NULL,
    energy_type_id       BIGINT       NOT NULL REFERENCES energy_types(id) ON DELETE RESTRICT,
    org_node_id          BIGINT       NOT NULL REFERENCES org_nodes(id)    ON DELETE RESTRICT,
    influx_measurement   VARCHAR(64)  NOT NULL,
    influx_tag_key       VARCHAR(64)  NOT NULL,
    influx_tag_value     VARCHAR(128) NOT NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (influx_measurement, influx_tag_key, influx_tag_value)
);

CREATE INDEX idx_meters_energy_type ON meters (energy_type_id);
CREATE INDEX idx_meters_org_node    ON meters (org_node_id);
CREATE INDEX idx_meters_enabled     ON meters (enabled) WHERE enabled = TRUE;

-- 计量层级（Sankey 用）。child 唯一：一个 meter 只能挂一个父 meter。
CREATE TABLE meter_topology (
    child_meter_id   BIGINT PRIMARY KEY REFERENCES meters(id) ON DELETE CASCADE,
    parent_meter_id  BIGINT NOT NULL    REFERENCES meters(id) ON DELETE RESTRICT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (child_meter_id <> parent_meter_id)
);

CREATE INDEX idx_meter_topology_parent ON meter_topology (parent_meter_id);
