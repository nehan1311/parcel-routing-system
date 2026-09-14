INSERT INTO routing_config_version (
    version,
    status,
    rules_json,
    created_by,
    created_at,
    activated_at,
    based_on_version_id
) VALUES (
    1,
    'ACTIVE',
    $$
    {
      "insurance": {
        "requiredAboveValueEur": 1000
      },
      "rules": [
        {
          "id": "heavy-department",
          "priority": 10,
          "condition": {
            "field": "weight_kg",
            "operator": "GT",
            "value": 10
          },
          "department": "Heavy"
        },
        {
          "id": "regular-department",
          "priority": 20,
          "condition": {
            "field": "weight_kg",
            "operator": "GT",
            "value": 1
          },
          "department": "Regular"
        },
        {
          "id": "mail-department",
          "priority": 30,
          "condition": {
            "field": "weight_kg",
            "operator": "LTE",
            "value": 1
          },
          "department": "Mail"
        }
      ]
    }
    $$::jsonb::text,
    'SYSTEM',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    NULL
);
