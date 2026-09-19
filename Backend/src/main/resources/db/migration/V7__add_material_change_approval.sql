ALTER TABLE routing_config_version
    ADD COLUMN material_change_approved_by VARCHAR(255),
    ADD COLUMN material_change_approved_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN material_change_approved_rules_json TEXT;
