ALTER TABLE parcel
    ADD COLUMN insurance_required BOOLEAN;

UPDATE parcel AS p
SET insurance_required = p.value_eur > (
    (config.rules_json::jsonb #>> '{insurance,requiredAboveValueEur}')::numeric
)
FROM routing_config_version AS config
WHERE p.routing_config_version_id = config.id;
