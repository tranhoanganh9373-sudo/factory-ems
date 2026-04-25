-- 平面图 + 点位（meter 在图上的坐标）
-- 图片落盘：ems_uploads/floorplans/{file_path}；元数据入库。
-- 点位坐标用比例存（x_ratio = x_px / image_width_px），换图后等比缩放不丢位。

CREATE TABLE floorplans (
    id              BIGSERIAL    PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    org_node_id     BIGINT       NOT NULL REFERENCES org_nodes(id) ON DELETE RESTRICT,
    file_path       VARCHAR(512) NOT NULL UNIQUE,
    content_type    VARCHAR(64)  NOT NULL,
    width_px        INT          NOT NULL CHECK (width_px > 0),
    height_px       INT          NOT NULL CHECK (height_px > 0),
    file_size_bytes BIGINT       NOT NULL CHECK (file_size_bytes > 0),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_by      BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_floorplans_org      ON floorplans (org_node_id);
CREATE INDEX idx_floorplans_enabled  ON floorplans (enabled) WHERE enabled = TRUE;

-- 点位：一个 meter 在一张平面图上至多一个点
CREATE TABLE floorplan_points (
    id            BIGSERIAL     PRIMARY KEY,
    floorplan_id  BIGINT        NOT NULL REFERENCES floorplans(id) ON DELETE CASCADE,
    meter_id      BIGINT        NOT NULL REFERENCES meters(id)     ON DELETE CASCADE,
    x_ratio       NUMERIC(7,6)  NOT NULL CHECK (x_ratio >= 0 AND x_ratio <= 1),
    y_ratio       NUMERIC(7,6)  NOT NULL CHECK (y_ratio >= 0 AND y_ratio <= 1),
    label         VARCHAR(64),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (floorplan_id, meter_id)
);

CREATE INDEX idx_floorplan_points_floorplan ON floorplan_points (floorplan_id);
CREATE INDEX idx_floorplan_points_meter     ON floorplan_points (meter_id);
