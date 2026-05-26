# Runtime Event and Audit Contracts

The decision runtime emits versioned contracts for every externally observable
decision artifact. Producers write a `schema_version` field and consumers must
ignore unknown fields within a supported schema version.

## Compatibility Rules

- Backward-compatible changes keep the same `schema_version` and may add fields.
- Consumers must read only the fields they need and ignore unknown fields.
- Breaking changes require a new `schema_version` and a dual-write or migration
  window before removing the old shape.
- Field names are snake_case. Enum values are the Kotlin enum names.
- Required fields may not be removed, renamed, or change type within a schema
  version.

## Kafka `decisions` Payload

Current `schema_version`: `1`

```json
{
  "schema_version": 1,
  "event_id": "evt-1",
  "action": "HOLD",
  "reason_codes": ["velocity_spike"],
  "score": {
    "score": 0.1,
    "raw_score": 0.14,
    "contributing_factors": [
      {"name": "fixed", "contribution": 0.1}
    ],
    "model_version": "fixed-v1",
    "latency_ms": 1.0,
    "degraded": false
  },
  "rule_evaluation_ids": ["velocity-spike"],
  "decided_at": "2026-01-01T12:00:05Z"
}
```

Required fields: `schema_version`, `event_id`, `action`, `reason_codes`,
`score`, `rule_evaluation_ids`, `decided_at`.

## Kafka `rule_evaluations` Payload

Current `schema_version`: `1`

```json
{
  "schema_version": 1,
  "event_id": "evt-1",
  "matches": [
    {
      "rule_id": "velocity-spike",
      "rule_version": 1,
      "mode": "ENFORCE",
      "priority": 100,
      "action_type": "REVIEW_QUEUE",
      "reason_code": "velocity_spike"
    }
  ],
  "skipped": [
    {
      "rule_id": "missing-feature",
      "rule_version": 1,
      "reason": "unknown_feature unavailable"
    }
  ],
  "resolved_action": {
    "rule_id": "velocity-spike",
    "rule_version": 1,
    "decision_action": "HOLD",
    "priority": 100,
    "action_type": "REVIEW_QUEUE",
    "reason_code": "velocity_spike"
  }
}
```

Required fields: `schema_version`, `event_id`, `matches`, `skipped`.
`resolved_action` is present only when an enforce rule wins action resolution.

## Kafka `shadow_evaluations` Payload

Current `schema_version`: `1`

```json
{
  "schema_version": 1,
  "event_id": "evt-1",
  "evaluations": [
    {
      "scorer_name": "live-model",
      "scorer_version": "v1",
      "role": "PRIMARY",
      "score": {
        "score": 0.1,
        "raw_score": 0.14,
        "contributing_factors": [
          {"name": "fixed", "contribution": 0.1}
        ],
        "model_version": "fixed-v1",
        "latency_ms": 1.0,
        "degraded": false
      }
    },
    {
      "scorer_name": "candidate-model",
      "scorer_version": "v2",
      "role": "SHADOW",
      "error": "timeout"
    }
  ]
}
```

Required fields: `schema_version`, `event_id`, `evaluations`. Each evaluation
requires `scorer_name`, `scorer_version`, `role`, and exactly one of `score` or
`error`.

## DynamoDB Decision Audit Row

Current `schema_version`: `1`

Partition key: `event_id`

| Attribute | Type | Required | Description |
| --- | --- | --- | --- |
| `event_id` | S | yes | Transaction event id. |
| `schema_version` | N | yes | Audit row schema version. |
| `decided_at` | S | yes | ISO-8601 decision timestamp. |
| `action` | S | yes | Final decision action. |
| `reason_codes` | L<S> | yes | Reason codes explaining the final action. |
| `score` | N | yes | Calibrated model score used by fallback decisions and rules. |
| `model_version` | S | yes | Model or scorer version that produced `score`. |
| `score_json` | S | yes | Full score object, including raw score, factors, latency, and degraded flag. |
| `rule_evaluation_ids` | L<S> | yes | Rule ids that matched during evaluation. |
| `features_json` | S | yes | Feature snapshot JSON keyed by feature name. |
| `rule_evaluation_json` | S | yes | Full versioned `rule_evaluations` payload for reconstruction. |

The audit row is intentionally denormalized. Top-level attributes support common
lookup and filtering. Embedded JSON preserves the complete decision context so a
decision can be reconstructed months later from DynamoDB alone.
