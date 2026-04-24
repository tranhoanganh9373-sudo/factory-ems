CREATE TABLE users (
    id                BIGSERIAL PRIMARY KEY,
    username          VARCHAR(64)  NOT NULL UNIQUE,
    password_hash     VARCHAR(128) NOT NULL,
    display_name      VARCHAR(128),
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_attempts   INT          NOT NULL DEFAULT 0,
    locked_until      TIMESTAMPTZ,
    last_login_at     TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE roles (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL UNIQUE,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255)
);

CREATE TABLE user_roles (
    user_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id  BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE node_permissions (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_node_id  BIGINT      NOT NULL,
    scope        VARCHAR(16) NOT NULL CHECK (scope IN ('SUBTREE','NODE_ONLY')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, org_node_id, scope)
);

CREATE INDEX idx_node_perm_user ON node_permissions(user_id);
CREATE INDEX idx_node_perm_node ON node_permissions(org_node_id);

CREATE TABLE refresh_tokens (
    jti         VARCHAR(64)  PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX idx_refresh_user    ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_expires ON refresh_tokens(expires_at);
