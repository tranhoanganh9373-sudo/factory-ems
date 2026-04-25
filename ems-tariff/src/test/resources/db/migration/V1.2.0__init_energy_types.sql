CREATE TABLE energy_types (
    id           BIGSERIAL    PRIMARY KEY,
    code         VARCHAR(32)  NOT NULL UNIQUE,
    name         VARCHAR(64)  NOT NULL,
    unit         VARCHAR(16)  NOT NULL,
    sort_order   INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO energy_types (code, name, unit, sort_order) VALUES
    ('ELEC',  '电',   'kWh', 10),
    ('WATER', '水',   'm3',  20),
    ('STEAM', '蒸汽', 't',   30);
