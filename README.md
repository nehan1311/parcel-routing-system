# Parcel Routing Service

## Architecture Decisions

- **Modular Spring Boot monolith:** Everything (routing, approval, config, security) lives in one deployable Spring Boot app, organized into separate packages, instead of separate services talking over the network. Example: `ParcelController`, `ApprovalController`, and `ConfigController` are three separate classes in the same app, not three separate deployments — so there's one thing to build, run, and debug.

- **Stateless Routing Engine:** The engine's only job is: given a parcel and a rule set, return a decision — it never opens a database connection itself. Example: `RoutingEngine.evaluate(parcel, config)` takes a `Parcel` object and a `RoutingConfig` object as plain Java arguments and returns a `RoutingDecision` — it can be called and tested with made-up in-memory objects, with no Postgres running at all.

- **Configuration-driven routing:** Rules aren't written as Java conditionals — they're stored as data (JSON) and read by the engine at evaluation time. Example: the rule "parcels over 10kg go to Heavy" is stored as `{ "field": "weight_kg", "operator": "GT", "value": 10, "department": "Heavy" }`, not as `if (weight > 10)` in the code. Changing the number 10 to 20 means editing that JSON value, not the codebase.

- **Versioned configuration:** Each full rule set is saved as its own numbered version (v1, v2, v3...), and exactly one is marked active. Example: v3 might have "Heavy = weight > 10kg." When the business changes the rule, that becomes v4. v3 still exists in the database, just no longer active — it isn't overwritten or erased.

- **Insurance as a separate gate:** Whether insurance approval is needed, and which department the parcel is predicted to go to, are two separate calculations done in the same step. Example: a parcel worth €2,000 and weighing 15kg gets `insuranceRequired: true` AND `predictedDepartment: "Heavy"` in the very same API response — the operator can see it's headed to Heavy before anyone approves the insurance.

- **Decision snapshotting:** At the moment a parcel is submitted, its routing result — matched rule, predicted department, and which config version was used — is saved permanently on that parcel's own record. Example: parcel #123 is submitted under config v3 and gets `routingConfigVersionId: 3` saved on it. Even if v4 becomes active the next day, parcel #123's stored record still says v3, and its final department is decided using that saved v3 result, not a fresh v4 calculation.

- **Priority-based rules:** Every rule has a number; the lowest number is checked first, and a matching rule stops the search. Two rules can't share the same number. Example: `heavy-department` has priority 10, `regular-department` has priority 20 — so Heavy is always checked before Regular. If someone tries to save a new rule that also has priority 10, the system rejects that configuration outright, instead of guessing which one should "win."

## Trade-offs

- **Monolith vs microservices:** One app is simpler to build, test, and deploy — for example, running `docker-compose up` starts the whole system with one command. The trade-off: you can't scale, say, just the routing part separately from the approval part the way you could with microservices, but at this scale that flexibility isn't needed.

- **Configurable rules vs hardcoded rules:** Storing rules as versioned JSON (rather than as `if` statements) means an Admin can add a rule like the `express-department` example below without a code change — but it comes with the cost of needing validation logic (checking for duplicate priorities, well-formed conditions) and a lifecycle (draft → validate → dry-run → activate) that a hardcoded `if` statement would never need.

- **Synchronous batch processing vs asynchronous processing:** Uploading a batch file and getting the full results back in the same HTTP response (e.g., `POST /api/parcels/batch` returns `{ total: 1000, succeeded: 987, failed: 13, results: [...] }` directly) is simple to build and simple for the operator to understand. The trade-off: for a very large file (say, 100,000 rows), the operator's request would sit open for a long time; a queue-based design (upload → get a job ID → poll for status) would handle that better but adds background-worker infrastructure this scope doesn't need.

- **JSONB configuration vs normalized tables:** Storing a whole rule set as one JSON blob per version (e.g., one `rules_json` column holding all of v3's rules together) makes it trivial to snapshot and version a complete configuration in one write. The trade-off: you lose the ability to easily query "which configs use priority 10" with a plain SQL `WHERE` clause the way you could if each rule were its own row in a normalized `rules` table.

- **Client-side pagination:** Returning the full batch results list in one response and letting the frontend page through them client-side (e.g., showing 50 rows at a time out of a 1,000-row `results` array already in memory) works fine for the current synchronous batch size. The trade-off: for a very large dataset, sending the entire list to the browser at once would be wasteful — server-side pagination (returning only page N of results per request) would scale better.

- **Lightweight monitoring:** Using Spring Boot Actuator plus structured logs and a scheduled anomaly check (e.g., comparing this week's department distribution — 20% Heavy — to a rolling baseline, and flagging it if Heavy suddenly jumps to 80%) gives real, useful signal with very little extra code. The trade-off: it's not a full dashboard system — there's no Grafana-style visual dashboard to click through, just logs and alerts.

## AI Usage

AI was used as a development assistant for design, implementation, testing, debugging, and review. Generated suggestions were reviewed, modified where necessary, and verified against the requirements and test results.

### Specific AI Uses

- **Routing engine design**
    - Purpose: Compare hardcoded `if/else` routing against a configuration-driven rule model.
    - Prompt: *"Design a configuration-driven Java routing engine where rules have priorities, conditions, operators, and departments. The engine should be stateless and independent of persistence."*
    - Modification: Adapted the suggested design to keep insurance approval separate from department routing, and to persist the routing decision together with the configuration version used.

- **Regression and boundary testing**
    - Purpose: Identify important boundary cases around the `1 kg`, `10 kg`, and `€1000` thresholds.
    - Cases identified: `1.01 kg`, `10.01 kg`, `€1000.01`.
    - Outcome: Implemented as JUnit tests and regression fixtures.

- **Batch processing**
    - Purpose: Reason about processing JSON/XML records independently, so one invalid record doesn't stop valid records from processing.
    - Prompt: *"Implement JSON and XML batch processing where an invalid parcel is reported as a per-record failure while valid parcels continue processing. Handle malformed documents separately."*
    - Modification: Adapted to the assessment's actual XML structure, the 10 MB upload limit, and the required batch response format.

- **Configuration safety**
    - Purpose: Design the `Draft → Validate → Dry-run → Activate` workflow and surface risks (e.g., duplicate priorities, configuration changes affecting parcels already in flight).
    - Outcome: Final lifecycle and snapshotting behavior implemented and verified independently.

- **Frontend implementation**
    - Purpose: Help implement React workflows for Operator, Admin, and Insurance-Approver roles.
    - Scope: Batch result tables, filtering, pagination, CSV export, error handling, responsive UI.
    - Verification: Reviewed against the backend API and checked through the production build and manual flows.

- **Security review**
    - Purpose: Review authentication, role-based authorization, credential handling, and API error cases.
    - Verification: Confirmed with role-based tests, including expected `403 Forbidden` responses for unauthorized operations.

- **Debugging**
    - Issue: A configuration dry-run was failing.
    - Root cause: The frontend was sending incorrect default rule data.
    - Fix: Traced by comparing the frontend payload against the backend contract, corrected the defaults, then confirmed the configuration validated successfully through the regression dry-run before activation.

### AI Limitations

- AI could suggest code that did not exactly match the existing API contracts or project structure.
- AI suggestions were reviewed against the actual codebase, requirements, and test results before being used.
- AI was not treated as authoritative for business decisions — rule precedence, insurance thresholds, approval states, configuration snapshotting, and activation safety were reasoned about and verified independently.
- Generated code was modified where necessary rather than accepted without review.

The goal was to use AI to accelerate implementation and exploration while retaining responsibility for the architecture, business logic, security decisions, testing, and final code.

## Extending the System With New Routing Rules

Routing rules are configuration-driven, so a normal new rule does not require changes to the core `RoutingEngine`.

Example:

```json
{
  "id": "express-department",
  "priority": 15,
  "condition": {
    "field": "value_eur",
    "operator": "GT",
    "value": 500
  },
  "department": "Express"
}
```

The new configuration follows:

```text
Create Draft → Validate → Dry-run → Activate
```

- **New routing rule:** Add the rule to the configuration — for example, the `express-department` rule above, added as a new entry in the draft's rule list.
- **New field:** Extend `FieldExtractor` to support the field — for example, if a new field like `package_type` needs to be checked, `FieldExtractor` needs one new lookup entry (`"package_type"` → the corresponding getter) before a rule can reference it.
- **New operator:** Extend `OperatorEvaluator` to support the operator — for example, adding support for a `BETWEEN` operator (checking that a value falls between two numbers) would require a new comparison case in `OperatorEvaluator`.
- **Safety:** Validate and dry-run the configuration before activation — for example, before the `express-department` rule above goes live, its draft is checked for valid syntax (Validate), then run against existing parcels to produce a before/after table like "parcel #88: Regular → Express" (Dry-run), and only then activated.

## Tests

- Unit tests cover the Routing Engine, conditions, operators, field extraction, rule precedence, and invalid configurations. Example: a test asserting that a parcel with `weight_kg: 10.01` matches the Heavy rule while `weight_kg: 10` does not.
- Boundary cases cover `1 kg`, `10 kg`, and the `€1000` insurance threshold. Example: a parcel valued at exactly `€1000` is tested to confirm it does *not* trigger insurance, while `€1000.01` does.
- Batch tests cover JSON, XML, invalid records, malformed documents, and oversized files. Example: a batch file where row 5 is missing the required `weight` field is tested to confirm row 5 is marked `FAILED` while the other rows still succeed.
- Workflow tests cover insurance approval and configuration lifecycle behavior. Example: a test that approves a `PENDING_APPROVAL` parcel and confirms its status becomes `ROUTED` with the previously predicted department.
- Security tests verify role-based access restrictions. Example: a test confirming an `OPERATOR` user gets a `403 Forbidden` when calling `POST /api/parcels/{id}/approve`, which requires `INSURANCE_APPROVER`.
- Regression fixtures verify expected routing outcomes and are reused during configuration dry-runs. Example: the same fixture parcel used in a unit test (say, a 15kg parcel expected to route to Heavy) is also loaded and re-checked whenever a new configuration draft is dry-run.
- The REST APIs were also manually verified end-to-end with PostgreSQL. Example: manually calling `POST /api/parcels` with a real request body against a running Postgres instance and confirming the row was persisted with the correct `status` and `department`.
