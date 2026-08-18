CREATE TABLE service_metadata (
    metadata_key VARCHAR(100) PRIMARY KEY,
    metadata_value VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO service_metadata (metadata_key, metadata_value)
VALUES ('schema-owner', 'jitong-im-server')
ON CONFLICT (metadata_key) DO NOTHING;
