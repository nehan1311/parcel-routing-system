CREATE TABLE parcel (
    id BIGSERIAL PRIMARY KEY,
    weight_kg DOUBLE PRECISION NOT NULL CHECK (weight_kg >= 0),
    value_eur DOUBLE PRECISION NOT NULL CHECK (value_eur >= 0),
    destination_country VARCHAR(255),
    attributes_json TEXT DEFAULT '{}',
    status VARCHAR(32) NOT NULL,
    department VARCHAR(255),
    predicted_department VARCHAR(255),
    matched_rule_id VARCHAR(255),
    routing_config_version_id BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    approved_by VARCHAR(255),
    approved_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_parcel_routing_config_version
        FOREIGN KEY (routing_config_version_id)
        REFERENCES routing_config_version (id),
    CONSTRAINT chk_parcel_routing_config_version
        CHECK (status = 'CREATED' OR routing_config_version_id IS NOT NULL)
);
