CREATE TABLE routing_config_version (
    id BIGSERIAL PRIMARY KEY,
    version INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    rules_json TEXT NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    activated_at TIMESTAMP WITH TIME ZONE,
    based_on_version_id BIGINT
);

CREATE UNIQUE INDEX ux_routing_config_version_active
    ON routing_config_version (status)
    WHERE status = 'ACTIVE';
