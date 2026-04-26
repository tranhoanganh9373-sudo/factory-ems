-- Hibernate `@Column(length = 7)` on `String yearMonth` expects VARCHAR(7),
-- but V1.2.2 created the column as CHAR(7) (bpchar). Schema validation fails
-- under `ddl-auto: validate` when both old and new schemas coexist (mock-data-generator
-- exercises this on a fresh dev DB while prod continues running on the legacy CHAR).
--
-- VARCHAR(7) is functionally equivalent for the 'YYYY-MM' format already enforced by
-- the existing CHECK constraint, and removes the implicit space-padding semantics of CHAR.

ALTER TABLE ts_rollup_monthly
    ALTER COLUMN year_month TYPE VARCHAR(7);
