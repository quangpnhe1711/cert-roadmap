-- Identity. Ownership is never encoded into a primary key and nothing user-owned
-- is globally unique, so tenancy can be introduced later as a coherent slice
-- rather than a scatter of nullable columns (decision D9).

CREATE TABLE app_user (
    id             UUID         PRIMARY KEY,
    email          TEXT         NOT NULL,
    password_hash  TEXT         NOT NULL,
    display_name   TEXT         NOT NULL,
    locale         TEXT         NOT NULL DEFAULT 'vi',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ,
    CONSTRAINT app_user_email_unique UNIQUE (email)
);

CREATE TABLE refresh_token (
    id            UUID         PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash    TEXT         NOT NULL,
    device_label  TEXT,
    expires_at    TIMESTAMPTZ  NOT NULL,
    revoked_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT refresh_token_hash_unique UNIQUE (token_hash)
);

CREATE INDEX refresh_token_user_idx ON refresh_token (user_id) WHERE revoked_at IS NULL;
