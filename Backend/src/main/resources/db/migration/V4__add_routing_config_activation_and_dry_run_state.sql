ALTER TABLE routing_config_version
    ADD COLUMN activated_by VARCHAR(255),
    ADD COLUMN dry_run_completed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN dry_run_passed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN dry_run_rules_json TEXT;
