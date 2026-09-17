import { useEffect, useMemo, useRef, useState } from "react";
import {
  activateConfigDraft, createConfigDraft, getActiveConfig, getConfigHistory, rollbackConfig, runConfigDryRun,
  approveParcel, authenticate, getPendingApprovals, isAuthenticationError,
  submitParcel, uploadBatch, validateConfigDraft,
} from "./api/parcelApi";
import { countries, countryName } from "./countries";

const parcelInitial = { weightKg: "", valueEur: "", destinationCountry: "" };
const blankRule = { id: "", priority: "", field: "weight_kg", operator: "GT", value: "", department: "" };
const configInitial = {
  insuranceThresholdEur: "1000",
  rules: [
    { ...blankRule, id: "heavy-department",   priority: "10", value: "10", department: "Heavy" },
    { ...blankRule, id: "regular-department", priority: "20", value: "1",  department: "Regular" },
    { ...blankRule, id: "mail-department",    priority: "30", operator: "LTE", value: "1", department: "Mail" },
  ],
};
const conditionFields = [
  { value: "weight_kg",           label: "Weight (kg)" },
  { value: "value_eur",           label: "Parcel Value (€)" },
  { value: "destination_country", label: "Destination Country" },
  { value: "attribute",           label: "Attribute" },
];
const operatorOptions = [
  { value: "GT",  label: "Greater than (>)" },
  { value: "GTE", label: "Greater than or equal (≥)" },
  { value: "LT",  label: "Less than (<)" },
  { value: "LTE", label: "Less than or equal (≤)" },
  { value: "EQ",  label: "Equal (=)" },
  { value: "NEQ", label: "Not equal (≠)" },
  { value: "IN",  label: "In (comma-separated)" },
];
const departments = ["Mail", "Regular", "Heavy"];
const identifierPattern = /^[A-Za-z][A-Za-z0-9_-]*$/;
const textOperatorValues = new Set(["EQ", "NEQ", "IN"]);

const errText  = (e) => e instanceof Error ? e.message : "The request could not be completed.";
const csvCell  = (v) => `"${String(v ?? "").replaceAll('"', '""')}"`;
const dateText = (v) => v
  ? new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(v))
  : "—";

function toConfig(form) {
  const convert = (rule) => {
    const numeric = ["weight_kg", "value_eur"].includes(rule.field);
    const values  = rule.value.split(",").map((v) => numeric ? Number(v.trim()) : v.trim());
    return rule.operator === "IN" ? values : values[0];
  };
  return {
    insuranceThresholdEur: Number(form.insuranceThresholdEur),
    rules: form.rules.map((r) => ({
      id: r.id.trim(), priority: Number(r.priority), department: r.department.trim(),
      condition: { field: r.field.trim(), operator: r.operator, value: convert(r) },
    })),
  };
}

function Status({ value }) {
  return <span className={`status ${value?.toLowerCase()}`}>{value?.replaceAll("_", " ")}</span>;
}

function ParcelDetailsModal({ parcel, onClose }) {
  return (
    <div className="details-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <section className="details-modal" role="dialog" aria-modal="true" aria-labelledby="parcel-details-title">
        <div className="details-modal-header">
          <div><p className="eyebrow">Routing details</p><h2 id="parcel-details-title">Parcel #{parcel.id}</h2></div>
          <button className="secondary details-close" type="button" onClick={onClose} aria-label="Close parcel details">Close</button>
        </div>
        <dl className="parcel-details-grid">
          <div><dt>Parcel ID</dt><dd>#{parcel.id}</dd></div>
          <div><dt>Status</dt><dd><Status value={parcel.status} /></dd></div>
          <div><dt>Department</dt><dd>{parcel.department ?? "Awaiting approval"}</dd></div>
          <div><dt>Predicted department</dt><dd>{parcel.predictedDepartment ?? "—"}</dd></div>
          <div><dt>Matched rule ID</dt><dd>{parcel.matchedRuleId ?? "—"}</dd></div>
          <div><dt>Routing config version</dt><dd>{parcel.routingConfigVersionId ?? "—"}</dd></div>
          <div><dt>Insurance required</dt><dd>{parcel.insuranceRequired ? "Yes" : "No"}</dd></div>
        </dl>
      </section>
    </div>
  );
}

function Toast({ type = "info", title, message, onClose }) {
  const icons = { success: "✓", error: "✕", info: "ℹ" };
  return (
    <div className={`toast ${type}`} role="alert">
      <span className="toast-icon">{icons[type]}</span>
      <div className="toast-body">
        {title && <div className="toast-title">{title}</div>}
        {message && <div>{message}</div>}
      </div>
      {onClose && <button className="secondary" style={{ minHeight: "auto", padding: "2px 8px", fontSize: ".75rem" }} onClick={onClose}>✕</button>}
    </div>
  );
}

function EmptyState({ icon = "📭", message }) {
  return (
    <div className="empty-state">
      <div className="empty-icon">{icon}</div>
      <p>{message}</p>
    </div>
  );
}

function SectionHeading({ title, hint, action }) {
  return (
    <div className="section-heading">
      <div>
        <h2>{title}</h2>
        {hint && <p className="hint">{hint}</p>}
      </div>
      {action}
    </div>
  );
}

function preventUnsignedNumberKeys(event) {
  if (["-", "+", "e", "E"].includes(event.key)) event.preventDefault();
}

function nonNegativeNumberError(value, label, { integer = false } = {}) {
  if (value.trim() === "") return `${label} is required.`;
  const number = Number(value);
  if (!Number.isFinite(number)) return `${label} must be a number.`;
  if (number < 0) return `${label} cannot be negative.`;
  if (integer && !Number.isInteger(number)) return `${label} must be a whole number.`;
  return "";
}

function validateConfigForm(form) {
  const errors = {};
  const thresholdError = nonNegativeNumberError(form.insuranceThresholdEur, "Insurance threshold", { integer: true });
  if (thresholdError) errors.insuranceThresholdEur = thresholdError;
  const ruleIds = new Map();

  form.rules.forEach((rule, index) => {
    const key = (field) => `rules.${index}.${field}`;
    const id = rule.id.trim();
    if (!id) errors[key("id")] = "Rule ID is required.";
    else if (!identifierPattern.test(id)) errors[key("id")] = "Use letters, numbers, hyphens, or underscores; start with a letter.";
    else if (id.length > 64) errors[key("id")] = "Rule ID must be 64 characters or fewer.";
    else if (ruleIds.has(id)) {
      errors[key("id")] = "Rule IDs must be unique.";
      errors[`rules.${ruleIds.get(id)}.id`] = "Rule IDs must be unique.";
    } else ruleIds.set(id, index);

    const priorityError = nonNegativeNumberError(rule.priority, "Priority", { integer: true });
    if (priorityError) errors[key("priority")] = priorityError;
    else if (Number(rule.priority) < 1) errors[key("priority")] = "Priority must be a positive integer.";

    const isAttribute = rule.field.startsWith("attribute:");
    const field = isAttribute ? "attribute" : rule.field;
    if (!conditionFields.some((option) => option.value === field)) errors[key("field")] = "Please select a valid condition field.";
    if (isAttribute && !identifierPattern.test(rule.field.slice("attribute:".length))) errors[key("attribute")] = "Attribute name must start with a letter and use only letters, numbers, hyphens, or underscores.";
    if (!operatorOptions.some((option) => option.value === rule.operator)
        || ((!['weight_kg', 'value_eur'].includes(rule.field)) && !textOperatorValues.has(rule.operator))) {
      errors[key("operator")] = "Please select a valid operator for this field.";
    }
    if (!departments.includes(rule.department)) errors[key("department")] = "Please select a department.";

    const values = rule.value.split(",").map((value) => value.trim());
    if (rule.operator === "IN" && values.some((value) => !value)) errors[key("value")] = "Enter one or more values.";
    else if (['weight_kg', 'value_eur'].includes(rule.field) && values.some((value) => !value || !Number.isFinite(Number(value))))
      errors[key("value")] = "Comparison value must be a number.";
    else if (rule.field !== "destination_country" && values.some((value) => !value)) errors[key("value")] = "Comparison value is required.";
  });
  return errors;
}

function FieldError({ message }) {
  return message ? <span className="field-error" role="alert">{message}</span> : null;
}

function decisionSummary(decision) {
  if (!decision) return "Unavailable";
  return `${decision.department ?? decision.predictedDepartment ?? "—"} · insurance ${decision.insuranceRequired ? "required" : "not required"}`;
}

function semanticChangeLabel(changeType) {
  const labels = {
    THRESHOLD_CHANGE: "Threshold changed",
    OPERATOR_CHANGE: "Operator changed",
    FIELD_CHANGE: "Field changed",
    PRIORITY_CHANGE: "Priority changed",
    DEPARTMENT_CHANGE: "Department changed",
    NEW_RULE: "New rule",
    REMOVED_RULE: "Removed rule",
  };
  return labels[changeType] ?? changeType;
}

function validationWarningLabel(type) {
  return {
    GAP: "Coverage gap",
    UNREACHABLE_RULE: "Unreachable rule",
    OVERLAP: "Rule overlap",
  }[type] ?? type;
}

function validationWarningDetail(warning) {
  if (warning.type === "GAP") return `${warning.field ?? "Numeric field"}: ${warning.message ?? "coverage gap detected"}`;
  if (warning.type === "UNREACHABLE_RULE") return `${warning.ruleId ?? "Rule"} is shadowed by ${warning.relatedRuleId ?? "a higher-priority rule"}`;
  if (warning.type === "OVERLAP") return `${warning.ruleId ?? "Rule"} overlaps ${warning.relatedRuleId ?? "another rule"}`;
  return warning.message ?? "Advisory warning";
}

function boundaryRangeLabel(range) {
  const weight = range.minWeightKg === range.maxWeightKg
    ? `${range.minWeightKg} kg`
    : `${range.minWeightKg}–${range.maxWeightKg} kg`;
  const value = range.minValueEur === range.maxValueEur
    ? `€${range.minValueEur}`
    : `€${range.minValueEur}–€${range.maxValueEur}`;
  return `${weight} · ${value}`;
}

function ActivationConfirmModal({ draft, activeConfiguration, changes, dryRun, onCancel, onConfirm, busy }) {
  const impact = dryRun?.historicalImpact;
  return (
    <div className="details-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !busy) onCancel(); }}>
      <section className="details-modal activation-modal" role="dialog" aria-modal="true" aria-labelledby="activate-config-title">
        <div className="details-modal-header">
          <div><p className="eyebrow">Confirmation required · Current active v{activeConfiguration.version}</p><h2 id="activate-config-title">Activate Configuration v{draft.version}?</h2></div>
          <button className="secondary details-close" type="button" onClick={onCancel} disabled={busy}>Close</button>
        </div>
        <div className="activation-section">
          <h3>Changes</h3>
          <ul>{changes.map((change) => <li key={change}>{change}</li>)}</ul>
        </div>
        <div className="activation-section">
          <h3>Regression</h3>
          <p className="regression-line success">✓ {dryRun.passedCases}/{dryRun.totalCases} passed</p>
        </div>
        <div className="activation-section">
          <h3>Historical impact</h3>
          <dl className="impact-summary">
            <div><dt>Analyzed</dt><dd>{impact?.parcelsAnalyzed ?? 0}</dd></div>
            <div><dt>Department changes</dt><dd>{impact?.departmentChanges ?? 0}</dd></div>
            <div><dt>Insurance changes</dt><dd>{impact?.insuranceChanges ?? 0}</dd></div>
          </dl>
        </div>
        <div className="action-row activation-actions">
          <button type="button" className="secondary" onClick={onCancel} disabled={busy}>Cancel</button>
          <button type="button" className="activate" onClick={onConfirm} disabled={busy}>{busy ? "Activating…" : "Activate"}</button>
        </div>
      </section>
    </div>
  );
}

function LoginPage({ onAuthenticated }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError]       = useState("");
  const [busy, setBusy]         = useState(false);

  async function login(e) {
    e.preventDefault();
    setError("");
    if (!username.trim() || !password) return setError("Enter both your username and password.");
    setBusy(true);
    try {
      const credentials = { username: username.trim(), password };
      const role = await authenticate(credentials);
      onAuthenticated({ credentials, role });
    } catch (err) {
      setError(isAuthenticationError(err) ? "Incorrect username or password." : errText(err));
    } finally { setBusy(false); }
  }

  return (
    <main className="login-shell">
      <div className="login-card">
        <div className="login-logo">PRS</div>
        <p className="eyebrow">Parcel Routing System</p>
        <h1>Welcome back</h1>
        <p className="lede">Sign in with your assigned account to access your workspace.</p>
        <form onSubmit={login} noValidate>
          <label>Username
            <input autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} placeholder="e.g. operator" />
          </label>
          <label>Password
            <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="••••••••" />
          </label>
          {error && <div className="login-error"><span>⚠</span> {error}</div>}
          <button type="submit" disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
        </form>
      </div>
    </main>
  );
}

function RouteParcelPage({ credentials, onAuthInvalid }) {
  const [parcel, setParcel] = useState(parcelInitial);
  const [result, setResult] = useState(null);
  const [toast, setToast]   = useState(null);
  const [busy, setBusy]     = useState(false);
  const [errors, setErrors] = useState({});

  function changeParcel(field, value) {
    setParcel((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: "" }));
  }

  async function sendParcel(e) {
    e.preventDefault();
    setToast(null); setResult(null);
    const weightKg = Number(parcel.weightKg), valueEur = Number(parcel.valueEur);
    const nextErrors = {
      weightKg: nonNegativeNumberError(parcel.weightKg, "Weight"),
      valueEur: nonNegativeNumberError(parcel.valueEur, "Declared value"),
      destinationCountry: parcel.destinationCountry ? "" : "Please select a destination country.",
    };
    if (Object.values(nextErrors).some(Boolean)) {
      setErrors(nextErrors);
      return;
    }
    setBusy(true);
    try {
      setResult(await submitParcel({ weightKg, valueEur, destinationCountry: parcel.destinationCountry, attributes: {} }, credentials));
    } catch (err) {
      if (isAuthenticationError(err)) onAuthInvalid();
      else setToast({ type: "error", message: errText(err) });
    } finally { setBusy(false); }
  }

  return (
    <div className="page-body">
      {toast && <Toast {...toast} onClose={() => setToast(null)} />}
      <section className="card">
        <SectionHeading title="Route a parcel" hint="Submit a single parcel for immediate routing." />
        <form onSubmit={sendParcel} noValidate>
          <div className="form-row">
            <label>Weight (kg)
              <input type="number" min="0" step="any" inputMode="decimal" required value={parcel.weightKg}
                aria-invalid={Boolean(errors.weightKg)} onKeyDown={preventUnsignedNumberKeys}
                onChange={(e) => changeParcel("weightKg", e.target.value)} placeholder="e.g. 5.2" />
              <FieldError message={errors.weightKg} />
            </label>
            <label>Declared value (EUR)
              <input type="number" min="0" step="any" inputMode="decimal" required value={parcel.valueEur}
                aria-invalid={Boolean(errors.valueEur)} onKeyDown={preventUnsignedNumberKeys}
                onChange={(e) => changeParcel("valueEur", e.target.value)} placeholder="e.g. 120" />
              <FieldError message={errors.valueEur} />
            </label>
          </div>
          <label>Destination country
            <select required value={parcel.destinationCountry} aria-invalid={Boolean(errors.destinationCountry)}
              onChange={(e) => changeParcel("destinationCountry", e.target.value)}>
              <option value="">Select a country</option>
              {countries.map(({ code, name }) => <option key={code} value={code}>{name}</option>)}
            </select>
            <FieldError message={errors.destinationCountry} />
          </label>
          <div><button disabled={busy}>{busy ? "Routing…" : "Route parcel"}</button></div>
        </form>
        {result && (
          <div className="result success">
            <div className="result-heading">
              <strong>Routing complete</strong>
              <Status value={result.status} />
            </div>
            {result.status === "PENDING_APPROVAL" && (
              <p className="approval-note">⚠ Insurance approval is required before this parcel can be routed.</p>
            )}
            <dl className="decision-grid">
              <div><dt>Department</dt><dd>{result.department ?? "Awaiting approval"}</dd></div>
              <div><dt>Predicted department</dt><dd>{result.predictedDepartment}</dd></div>
              <div><dt>Matched rule</dt><dd>{result.matchedRuleId}</dd></div>
              <div><dt>Config version</dt><dd>{result.routingConfigVersionId}</dd></div>
              <div><dt>Insurance required</dt><dd>{result.insuranceRequired ? "Yes" : "No"}</dd></div>
            </dl>
          </div>
        )}
      </section>
    </div>
  );
}

function BatchUploadPage({ credentials, onAuthInvalid }) {
  const [file, setFile]               = useState(null);
  const [batch, setBatch]             = useState(null);
  const [toast, setToast]             = useState(null);
  const [busy, setBusy]               = useState(false);
  const [batchPage, setBatchPage]     = useState(1);
  const [batchSearch, setBatchSearch] = useState("");
  const [batchStatus, setBatchStatus] = useState("ALL");
  const [batchDept, setBatchDept]     = useState("ALL");
  const [selectedOutcome, setSelectedOutcome] = useState(null);
  const [fileError, setFileError] = useState("");

  const batchOutcomes = batch?.createdParcels ?? [];
  const batchDepts = useMemo(() =>
    [...new Set(batchOutcomes.map((i) => i.department ?? i.predictedDepartment).filter(Boolean))].sort(),
    [batchOutcomes]);
  const filtered = useMemo(() => batchOutcomes.filter((i) =>
    String(i.id).includes(batchSearch.trim()) &&
    (batchStatus === "ALL" || i.status === batchStatus) &&
    (batchDept === "ALL" || i.department === batchDept || (!i.department && i.predictedDepartment === batchDept))
  ), [batchOutcomes, batchSearch, batchStatus, batchDept]);
  const dist = useMemo(() => batchOutcomes.reduce((c, i) => {
    if (i.status === "PENDING_APPROVAL") c.pending++;
    else if (i.department === "Mail")    c.mail++;
    else if (i.department === "Regular") c.regular++;
    else if (i.department === "Heavy")   c.heavy++;
    return c;
  }, { mail: 0, regular: 0, heavy: 0, pending: 0 }), [batchOutcomes]);
  const pageCount   = Math.max(1, Math.ceil(filtered.length / 20));
  const visibleRows = useMemo(() => filtered.slice((batchPage - 1) * 20, batchPage * 20), [filtered, batchPage]);

  useEffect(() => {
    if (!selectedOutcome) return undefined;
    const closeOnEscape = (event) => { if (event.key === "Escape") setSelectedOutcome(null); };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [selectedOutcome]);

  async function sendBatch(e) {
    e.preventDefault();
    setToast(null); setBatch(null); setSelectedOutcome(null); setBatchPage(1); setBatchSearch(""); setBatchStatus("ALL"); setBatchDept("ALL");
    if (!file || !/\.(json|xml)$/i.test(file.name)) {
      setFileError("Choose a .json or .xml batch file.");
      return;
    }
    setBusy(true);
    try {
      const res = await uploadBatch(file, credentials);
      setBatch(res);
      setToast({ type: "success", title: "Batch complete", message: `${res.successfulRecords} of ${res.totalRecords} records processed.` });
    } catch (err) {
      if (isAuthenticationError(err)) onAuthInvalid();
      else setToast({ type: "error", message: errText(err) });
    } finally { setBusy(false); }
  }

  function exportCsv() {
    const headers = ["Parcel ID","Status","Department","Predicted Department","Matched Rule ID","Config Version","Insurance Required"];
    const rows    = batchOutcomes.map((i) => [i.id, i.status, i.department, i.predictedDepartment, i.matchedRuleId, i.routingConfigVersionId, i.insuranceRequired ? "Yes" : "No"]);
    const csv     = [headers, ...rows].map((r) => r.map(csvCell).join(",")).join("\r\n");
    const url     = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    const a       = Object.assign(document.createElement("a"), { href: url, download: `batch-${new Date().toISOString().replaceAll(":", "-")}.csv` });
    document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
  }

  return (
    <div className="page-body">
      {toast && <Toast {...toast} onClose={() => setToast(null)} />}
      <section className="card">
        <SectionHeading title="Upload batch" hint="Process multiple parcels from a JSON or XML file. Invalid records are reported individually." />
        <form onSubmit={sendBatch}>
          <label>Batch file (.json or .xml)
            <input type="file" accept=".json,.xml,application/json,application/xml,text/xml"
              aria-invalid={Boolean(fileError)} onChange={(e) => {
                const selectedFile = e.target.files?.[0] ?? null;
                if (selectedFile && !/\.(json|xml)$/i.test(selectedFile.name)) {
                  setFile(null); setFileError("Choose a .json or .xml batch file.");
                } else { setFile(selectedFile); setFileError(""); }
              }} />
            <FieldError message={fileError} />
          </label>
          <div><button disabled={busy}>{busy ? "Uploading…" : "Upload batch"}</button></div>
        </form>

        {batch && (
          <div className="result success" style={{ marginTop: 20 }}>
            <div className="batch-result-heading">
              <div>
                <strong>Batch processed</strong>
                <dl className="batch-summary">
                  <div><dt>Total</dt><dd>{batch.totalRecords}</dd></div>
                  <div><dt>Successful</dt><dd>{batch.successfulRecords}</dd></div>
                  <div><dt>Failed</dt><dd>{batch.failedRecords}</dd></div>
                </dl>
              </div>
              <button className="secondary export-csv" type="button" onClick={exportCsv} disabled={batchOutcomes.length === 0}>Export CSV</button>
            </div>

            {batchOutcomes.length > 0 && (
              <div className="batch-outcomes">
                <div className="batch-outcomes-heading">
                  <div>
                    <h3>Routing outcomes</h3>
                    <p className="hint">Showing {visibleRows.length} of {filtered.length} matching outcomes.</p>
                  </div>
                  <dl className="routing-distribution">
                    <div><dt>Mail</dt><dd>{dist.mail}</dd></div>
                    <div><dt>Regular</dt><dd>{dist.regular}</dd></div>
                    <div><dt>Heavy</dt><dd>{dist.heavy}</dd></div>
                    <div><dt>Pending</dt><dd>{dist.pending}</dd></div>
                  </dl>
                </div>
                <div className="batch-filters">
                  <label>Search parcel ID
                    <input value={batchSearch} onChange={(e) => { setBatchSearch(e.target.value); setBatchPage(1); }} inputMode="numeric" placeholder="e.g. 1024" />
                  </label>
                  <label>Status
                    <select value={batchStatus} onChange={(e) => { setBatchStatus(e.target.value); setBatchPage(1); }}>
                      <option value="ALL">All statuses</option>
                      <option value="ROUTED">Routed</option>
                      <option value="PENDING_APPROVAL">Pending approval</option>
                    </select>
                  </label>
                  <label>Department
                    <select value={batchDept} onChange={(e) => { setBatchDept(e.target.value); setBatchPage(1); }}>
                      <option value="ALL">All departments</option>
                      {batchDepts.map((d) => <option key={d} value={d}>{d}</option>)}
                    </select>
                  </label>
                </div>
                <div className="batch-table-wrap">
                  <table className="batch-outcomes-table">
                    <thead>
                      <tr>
                        <th>Parcel ID</th><th>Status</th><th>Department</th>
                        <th>Predicted Dept.</th><th>Matched Rule</th>
                        <th>Config Ver.</th><th>Insurance</th><th><span className="sr-only">Actions</span></th>
                      </tr>
                    </thead>
                    <tbody>
                      {visibleRows.map((row) => (
                        <tr key={row.id}>
                          <td data-label="Parcel ID">#{row.id}</td>
                          <td data-label="Status"><Status value={row.status} /></td>
                          <td data-label="Department">{row.status === "PENDING_APPROVAL" ? <span className="insurance-flag">Awaiting approval</span> : row.department}</td>
                          <td data-label="Predicted Dept.">{row.predictedDepartment}</td>
                          <td data-label="Matched Rule">{row.matchedRuleId}</td>
                          <td data-label="Config Ver.">{row.routingConfigVersionId}</td>
                          <td data-label="Insurance">{row.status === "PENDING_APPROVAL" ? <span className="insurance-flag">Required</span> : "No"}</td>
                          <td data-label="Action"><button className="secondary details-button" type="button" onClick={() => setSelectedOutcome(row)}>View details</button></td>
                        </tr>
                      ))}
                      {visibleRows.length === 0 && (
                        <tr><td className="batch-empty" colSpan="8">No outcomes match these filters.</td></tr>
                      )}
                    </tbody>
                  </table>
                </div>
                {pageCount > 1 && (
                  <nav className="batch-pagination" aria-label="Batch pages">
                    <button className="secondary" onClick={() => setBatchPage((p) => Math.max(1, p - 1))} disabled={batchPage === 1}>← Previous</button>
                    <div className="page-numbers">
                      {Array.from({ length: pageCount }, (_, i) => i + 1).map((p) => (
                        <button key={p} className={p === batchPage ? "selected" : "secondary"} onClick={() => setBatchPage(p)} aria-current={p === batchPage ? "page" : undefined}>{p}</button>
                      ))}
                    </div>
                    <button className="secondary" onClick={() => setBatchPage((p) => Math.min(pageCount, p + 1))} disabled={batchPage === pageCount}>Next →</button>
                  </nav>
                )}
              </div>
            )}

            {batch.errors?.length > 0 && (
              <div className="record-errors">
                <strong>Record errors ({batch.errors.length})</strong>
                <ul>{batch.errors.map((e) => <li key={`${e.recordNumber}-${e.message}`}>Record {e.recordNumber}: {e.message}</li>)}</ul>
              </div>
            )}
          </div>
        )}
      </section>
      {selectedOutcome && <ParcelDetailsModal parcel={selectedOutcome} onClose={() => setSelectedOutcome(null)} />}
    </div>
  );
}

function ConfigPage({ credentials, onAuthInvalid, sharedState }) {
  const { form, setForm, draft, setDraft, validation, setValidation, dryRun, setDryRun, toast, setToast, busy, setBusy } = sharedState;
  const ready = validation?.valid && dryRun?.failedCases === 0;
  const [impactExpanded, setImpactExpanded] = useState(false);
  const [ruleChangesExpanded, setRuleChangesExpanded] = useState(false);
  const [acknowledgedRuleChanges, setAcknowledgedRuleChanges] = useState(new Set());
  const [activationError, setActivationError] = useState("");
  const [confirmActivation, setConfirmActivation] = useState(false);
  const [activeConfiguration, setActiveConfiguration] = useState(null);
  const [formErrors, setFormErrors] = useState({});
  const [reason, setReason] = useState("");
  const historicalImpact = dryRun?.historicalImpact;
  const changedRuleDiffs = (dryRun?.semanticDiff ?? []).filter((diff) => diff?.changeType !== "NO_CHANGE");
  const boundarySimulation = dryRun?.boundarySimulation;
  const boundaryChanges = boundarySimulation?.changedRanges ?? [];
  const draftConfiguration = useMemo(() => toConfig(form), [form]);
  const activationChanges = useMemo(() => {
    if (!activeConfiguration?.configuration) return [];
    const active = activeConfiguration.configuration;
    const activeRules = new Map(active.rules.map((rule) => [rule.id, rule]));
    const changes = [];
    if (draftConfiguration.insuranceThresholdEur !== active.insuranceThresholdEur)
      changes.push(`Insurance threshold: €${active.insuranceThresholdEur} → €${draftConfiguration.insuranceThresholdEur}`);
    draftConfiguration.rules.forEach((rule) => {
      const previous = activeRules.get(rule.id);
      if (!previous) changes.push(`New ${rule.department} rule: ${rule.id}`);
      else if (previous.condition.field === "weight_kg" && rule.condition.field === "weight_kg" && previous.condition.value !== rule.condition.value)
        changes.push(`${rule.department} threshold: ${previous.condition.value} kg → ${rule.condition.value} kg`);
      else if (previous.department !== rule.department || previous.priority !== rule.priority
          || previous.condition.field !== rule.condition.field || previous.condition.operator !== rule.condition.operator
          || JSON.stringify(previous.condition.value) !== JSON.stringify(rule.condition.value))
        changes.push(`Rule ${rule.id} updated`);
      activeRules.delete(rule.id);
    });
    activeRules.forEach((rule) => changes.push(`Removed ${rule.department} rule: ${rule.id}`));
    return changes.length ? changes : ["No threshold or rule changes detected."];
  }, [activeConfiguration, draftConfiguration]);

  const changeRule = (i, k, v) => {
    setForm((f) => ({ ...f, rules: f.rules.map((r, idx) => idx === i ? { ...r, [k]: v } : r) }));
    setFormErrors({});
  };
  const changeRuleField = (i, field) => {
    setForm((current) => ({ ...current, rules: current.rules.map((rule, index) => {
      if (index !== i) return rule;
      const nextField = field === "attribute" ? "attribute:" : field;
      return { ...rule, field: nextField, operator: ["weight_kg", "value_eur"].includes(nextField) || textOperatorValues.has(rule.operator) ? rule.operator : "EQ", value: "" };
    }) }));
    setFormErrors({});
  };
  const changeAttrName  = (i, name)  => changeRule(i, "field", `attribute:${name}`);

  async function create(e) {
    e.preventDefault();
    setToast(null);
    const errors = validateConfigForm(form);
    if (Object.keys(errors).length > 0) {
      setFormErrors(errors);
      setToast({ type: "error", title: "Check the configuration", message: "Correct the highlighted fields before creating the draft." });
      return;
    }
    const payload = {
      ...toConfig(form),
      ...(reason.trim() ? { reason: reason.trim() } : {}),
    };
    setBusy("create");
    try {
      const next = await createConfigDraft(payload, credentials);
      setDraft(next); setValidation(null); setDryRun(null); setImpactExpanded(false); setConfirmActivation(false); setActiveConfiguration(null); setAcknowledgedRuleChanges(new Set()); setActivationError(""); setFormErrors({});
      setToast({ type: "info", title: `Draft v${next.version} created`, message: "Validate and run a dry-run before activating." });
    } catch (err) { if (isAuthenticationError(err)) onAuthInvalid(); else setToast({ type: "error", message: errText(err) }); }
    finally { setBusy(""); }
  }

  async function validate() {
    setBusy("validate");
    try {
      const next = await validateConfigDraft(draft.version, credentials);
      setValidation(next);
      setToast(next.valid
        ? { type: "success", title: "Validation passed", message: "The configuration is valid. Run a dry-run next." }
        : { type: "error",   title: "Validation failed", message: "Fix the listed errors before proceeding." });
    } catch (err) {
      if (isAuthenticationError(err)) onAuthInvalid();
      else { const msg = errText(err); setValidation({ valid: false, errors: [msg] }); setToast({ type: "error", message: msg }); }
    } finally { setBusy(""); }
  }

  async function dryRunDraft() {
    setBusy("dryrun");
    try {
      const next = await runConfigDryRun(draft.version, credentials);
      setDryRun(next); setImpactExpanded(false); setAcknowledgedRuleChanges(new Set()); setActivationError("");
      setToast(next.failedCases === 0
        ? { type: "success", title: "Dry-run passed", message: "All regression cases passed. You can now activate." }
        : { type: "error",   title: "Dry-run failed",  message: `${next.failedCases} case(s) failed. Activation is blocked.` });
    } catch (err) { if (isAuthenticationError(err)) onAuthInvalid(); else setToast({ type: "error", message: errText(err) }); }
    finally { setBusy(""); }
  }

  async function activate() {
    setBusy("activate");
    setActivationError("");
    try {
      const active = await activateConfigDraft(draft.version, credentials, [...acknowledgedRuleChanges]);
      setToast({ type: "success", title: `Version ${active.version} is now ACTIVE`, message: "Live routing is using this configuration." });
      setDraft(null); setValidation(null); setDryRun(null); setConfirmActivation(false); setImpactExpanded(false); setActiveConfiguration(null);
    } catch (err) {
      if (isAuthenticationError(err)) onAuthInvalid();
      else if (err?.status === 409 && err.message?.includes("unacknowledged field/operator changes")) {
        setActivationError(`Activation blocked: ${errText(err)}`);
      } else setToast({ type: "error", message: errText(err) });
    }
    finally { setBusy(""); }
  }

  async function openActivationConfirmation() {
    setBusy("load-active");
    try {
      setActiveConfiguration(await getActiveConfig(credentials));
      setConfirmActivation(true);
    } catch (err) {
      if (isAuthenticationError(err)) onAuthInvalid();
      else setToast({ type: "error", message: `Could not load the active configuration: ${errText(err)}` });
    } finally { setBusy(""); }
  }

  return (
    <div className="page-body">
      {toast && <Toast {...toast} onClose={() => setToast(null)} />}
      <section className="card">
        <div className="section-heading">
          <div>
            <h2>Create configuration draft</h2>
            <p className="hint">Draft → Validate → Dry-run → Activate. Drafts never affect live routing.</p>
          </div>
          {draft && <Status value={draft.status} />}
        </div>

        <ol className="workflow">
          <li className={draft ? "done" : "current"}>Draft</li>
          <li className={validation?.valid ? "done" : draft ? "current" : ""}>Validate</li>
          <li className={dryRun?.failedCases === 0 ? "done" : validation?.valid ? "current" : ""}>Dry-run</li>
          <li className={ready ? "current" : ""}>Activate</li>
        </ol>

        <form onSubmit={create} noValidate>
          <fieldset disabled={Boolean(draft) || Boolean(busy)} style={{ border: "none", margin: 0, padding: 0 }}>
            <label>Insurance approval threshold (EUR)
              <input type="number" min="0" step="1" inputMode="numeric" required value={form.insuranceThresholdEur}
                aria-invalid={Boolean(formErrors.insuranceThresholdEur)} onKeyDown={preventUnsignedNumberKeys}
                onChange={(e) => { setForm({ ...form, insuranceThresholdEur: e.target.value }); setFormErrors({}); }} />
              <span className="field-help">Parcels declared above this value require insurance approval before routing.</span>
              <FieldError message={formErrors.insuranceThresholdEur} />
            </label>
            <label className="draft-reason">Reason <span className="optional-label">(optional)</span>
              <textarea maxLength="500" rows="3" value={reason}
                onChange={(e) => setReason(e.target.value)} placeholder="Why is this configuration change being made?" />
              <span className="field-help">Up to 500 characters. This is stored for review only.</span>
            </label>
            <div className="rule-list">
              <div className="rule-list-heading">
                <div>
                  <h3>Routing rules</h3>
                  <p className="hint">Rules are evaluated in ascending priority order (lower = higher precedence).</p>
                </div>
                <button className="secondary" type="button" onClick={() => { setForm({ ...form, rules: [...form.rules, { ...blankRule }] }); setFormErrors({}); }}>+ Add rule</button>
              </div>
              {form.rules.map((rule, idx) => {
                const isAttr   = rule.field.startsWith("attribute:");
                const selField = isAttr ? "attribute" : rule.field;
                const isNumericField = ["weight_kg", "value_eur"].includes(rule.field);
                const availableOperators = isNumericField ? operatorOptions : operatorOptions.filter((option) => textOperatorValues.has(option.value));
                return (
                  <fieldset className="rule" key={idx}>
                    <legend>Rule {idx + 1}</legend>
                    <div className="rule-fields">
                      <label>Rule ID
                        <input required maxLength="64" pattern="[A-Za-z][A-Za-z0-9_-]*" value={rule.id} aria-invalid={Boolean(formErrors[`rules.${idx}.id`])}
                          onChange={(e) => changeRule(idx, "id", e.target.value)} placeholder="e.g. heavy-department" />
                        <span className="field-help">Unique, stable identifier.</span>
                        <FieldError message={formErrors[`rules.${idx}.id`]} />
                      </label>
                      <label>Priority
                        <input required type="number" min="1" step="1" inputMode="numeric" value={rule.priority} aria-invalid={Boolean(formErrors[`rules.${idx}.priority`])}
                          onKeyDown={preventUnsignedNumberKeys} onChange={(e) => changeRule(idx, "priority", e.target.value)} placeholder="e.g. 10" />
                        <span className="field-help">Lower = higher precedence.</span>
                        <FieldError message={formErrors[`rules.${idx}.priority`]} />
                      </label>
                      <label>Department
                        <select required value={rule.department} aria-invalid={Boolean(formErrors[`rules.${idx}.department`])} onChange={(e) => changeRule(idx, "department", e.target.value)}>
                          <option value="" disabled>Select department</option>
                          {departments.map((d) => <option key={d} value={d}>{d}</option>)}
                        </select>
                        <FieldError message={formErrors[`rules.${idx}.department`]} />
                      </label>
                      <label>Condition field
                        <select required value={selField} aria-invalid={Boolean(formErrors[`rules.${idx}.field`])} onChange={(e) => changeRuleField(idx, e.target.value)}>
                          {conditionFields.map((f) => <option key={f.value} value={f.value}>{f.label}</option>)}
                        </select>
                        <FieldError message={formErrors[`rules.${idx}.field`]} />
                      </label>
                      {isAttr && (
                        <label>Attribute name
                          <input required maxLength="64" pattern="[A-Za-z][A-Za-z0-9_-]*" value={rule.field.slice("attribute:".length)} aria-invalid={Boolean(formErrors[`rules.${idx}.attribute`])}
                            onChange={(e) => changeAttrName(idx, e.target.value)} placeholder="e.g. fragile" />
                          <span className="field-help">Matches a named parcel attribute.</span>
                          <FieldError message={formErrors[`rules.${idx}.attribute`]} />
                        </label>
                      )}
                      <label>Operator
                        <select required value={rule.operator} aria-invalid={Boolean(formErrors[`rules.${idx}.operator`])} onChange={(e) => changeRule(idx, "operator", e.target.value)}>
                          {availableOperators.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                        </select>
                        <FieldError message={formErrors[`rules.${idx}.operator`]} />
                      </label>
                      <label>Comparison value
                        {rule.field === "destination_country" ? (
                          rule.operator === "IN" ? (
                            <select required multiple size="5" aria-invalid={Boolean(formErrors[`rules.${idx}.value`] )}
                              value={rule.value.split(",").map((value) => value.trim()).filter(Boolean)}
                              onChange={(e) => changeRule(idx, "value", [...e.target.selectedOptions].map((option) => option.value).join(","))}>
                              {countries.map(({ code, name }) => <option key={code} value={code}>{name}</option>)}
                            </select>
                          ) : (
                            <select required value={rule.value.split(",")[0].trim()} aria-invalid={Boolean(formErrors[`rules.${idx}.value`])}
                              onChange={(e) => changeRule(idx, "value", e.target.value)}>
                              <option value="">Select a country</option>
                              {countries.map(({ code, name }) => <option key={code} value={code}>{name}</option>)}
                            </select>
                          )
                        ) : (
                          <input required type={isNumericField && rule.operator !== "IN" ? "number" : "text"}
                            step={isNumericField && rule.operator !== "IN" ? "any" : undefined} inputMode={isNumericField ? "decimal" : undefined}
                            maxLength={!isNumericField ? "120" : rule.operator === "IN" ? "250" : undefined} value={rule.value} aria-invalid={Boolean(formErrors[`rules.${idx}.value`])}
                            onChange={(e) => changeRule(idx, "value", e.target.value)} placeholder={rule.operator === "IN" ? "Comma-separated values" : "Enter a value"} />
                        )}
                        <span className="field-help">Must be compatible with the selected field.</span>
                        <FieldError message={formErrors[`rules.${idx}.value`]} />
                      </label>
                    </div>
                    <div className="rule-footer">
                      <button className="danger-link" type="button" disabled={form.rules.length === 1}
                        onClick={() => { setForm({ ...form, rules: form.rules.filter((_, i) => i !== idx) }); setFormErrors({}); }}>
                        Remove rule
                      </button>
                    </div>
                  </fieldset>
                );
              })}
            </div>
          </fieldset>
          {!draft
            ? <div><button disabled={Boolean(busy)}>{busy === "create" ? "Creating draft…" : "Create draft"}</button></div>
            : <button type="button" className="secondary" onClick={() => { setDraft(null); setValidation(null); setDryRun(null); setImpactExpanded(false); setConfirmActivation(false); setActiveConfiguration(null); setAcknowledgedRuleChanges(new Set()); setActivationError(""); setReason(""); }}>Start new draft</button>
          }
        </form>

        {draft && (
          <div className="action-row">
            <button type="button" onClick={validate} disabled={Boolean(busy)}>{busy === "validate" ? "Validating…" : "Validate"}</button>
            <button type="button" onClick={dryRunDraft} disabled={Boolean(busy) || !validation?.valid}>{busy === "dryrun" ? "Running dry-run…" : "Run dry-run"}</button>
            {ready && <button type="button" className="activate" onClick={openActivationConfirmation} disabled={Boolean(busy)}>{busy === "load-active" ? "Loading confirmation…" : "Activate"}</button>}
          </div>
        )}

        {validation && (
          <div className={`result ${validation.valid ? "success" : "error"}`}>
            <strong>{validation.valid ? "✓ Validation passed" : "✕ Validation errors"}</strong>
            <p className="hint" style={{ marginTop: 6 }}>{validation.valid ? "The configuration is structurally valid." : "Correct the issues below before proceeding."}</p>
            {validation.errors?.length > 0 && <ul style={{ marginTop: 8, paddingLeft: 18 }}>{validation.errors.map((e) => <li key={e} style={{ fontSize: ".83rem" }}>{e}</li>)}</ul>}
            {validation.valid && validation.warnings?.length > 0 && (
              <div className="validation-warnings">
                <strong>{validation.warnings.length} advisory {validation.warnings.length === 1 ? "warning" : "warnings"} — configuration is still valid</strong>
                <ul>
                  {validation.warnings.map((warning, index) => (
                    <li key={`${warning.type}-${warning.ruleId ?? warning.field ?? "warning"}-${index}`}>
                      <strong>{validationWarningLabel(warning.type)}</strong>: {validationWarningDetail(warning)}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}

        {dryRun && (
          <div className={`result ${dryRun.failedCases ? "error" : "success"}`}>
            <h3>Regression Tests</h3>
            <p className={`regression-line ${dryRun.failedCases === 0 ? "success" : "failure"}`}>
              {dryRun.failedCases === 0 ? "✓" : "✕"} {dryRun.passedCases}/{dryRun.totalCases} passed
            </p>
            <p className="hint">This simulation does not affect live routing.</p>
            {dryRun.failedCases > 0 && (
              <p className="dry-run-guidance">
                Dry-run failed because this configuration changes the expected routing behavior for one or more regression cases. Review the failed cases before activation.
                <br /><br />
                If this is an intentional business-rule change, the corresponding regression expectations must be updated as part of the rule-change process before this configuration can be activated.
              </p>
            )}
            {dryRun.failures?.length > 0 && (
              <div className="record-errors">
                <strong>Failure details</strong>
                <ul>
                  {dryRun.failures.map((f) => (
                    <li key={f.caseName}>
                      <strong>{f.caseName}</strong>
                      {f.error ? `: ${f.error}` : <div className="dry-run-difference">
                        <div><span>Expected result</span>{decisionSummary(f.expected)}</div>
                        <div><span>Actual result</span>{decisionSummary(f.actual)}</div>
                        <div><span>Matched rule</span>{f.expected?.matchedRuleId ?? "—"} → {f.actual?.matchedRuleId ?? "—"}</div>
                      </div>}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <div className="rule-changes">
              <div className="impact-heading">
                <div>
                  <h3>Rule Changes</h3>
                  {changedRuleDiffs.length === 0
                    ? <p className="impact-empty">No rule changes detected.</p>
                    : <p className="hint">{changedRuleDiffs.length} {changedRuleDiffs.length === 1 ? "rule" : "rules"} changed.</p>}
                </div>
              </div>
              {changedRuleDiffs.length > 0 && <>
                <button type="button" className="secondary impact-toggle" onClick={() => setRuleChangesExpanded((expanded) => !expanded)}>
                  {ruleChangesExpanded ? "Hide rule changes" : `View rule changes (${changedRuleDiffs.length})`}
                </button>
                {ruleChangesExpanded && <div className="batch-table-wrap semantic-diff-table-wrap">
                  <table className="batch-outcomes-table semantic-diff-table">
                    <thead><tr><th>Rule ID</th><th>Change type</th><th>Acknowledgment</th></tr></thead>
                    <tbody>{changedRuleDiffs.map((diff) => (
                      <tr key={diff.ruleId} className={diff.crossesNumericTextBoundary ? "semantic-diff-boundary" : undefined}>
                        <td data-label="Rule ID">{diff.ruleId ?? "—"}</td>
                        <td data-label="Change type">{semanticChangeLabel(diff.changeType)}{diff.crossesNumericTextBoundary && " · Numeric/text boundary"}</td>
                        <td data-label="Acknowledgment">
                          {diff.changeType === "FIELD_CHANGE" || diff.changeType === "OPERATOR_CHANGE"
                            ? <label className="rule-change-acknowledgment">
                                <input
                                  type="checkbox"
                                  checked={acknowledgedRuleChanges.has(diff.ruleId)}
                                  onChange={(event) => setAcknowledgedRuleChanges((current) => {
                                    const next = new Set(current);
                                    if (event.target.checked) next.add(diff.ruleId);
                                    else next.delete(diff.ruleId);
                                    return next;
                                  })}
                                />
                                Acknowledge
                              </label>
                            : "—"}
                        </td>
                      </tr>
                    ))}</tbody>
                  </table>
                </div>}
              </>}
              {activationError && <p className="dry-run-guidance activation-acknowledgment-error">{activationError}</p>}
            </div>
            <div className="boundary-impact">
              <div className="impact-heading">
                <div>
                  <h3>Boundary Impact</h3>
                  <p className="hint">Synthetic weight × value grid showing where routing decisions change.</p>
                </div>
              </div>
              <dl className="impact-summary boundary-impact-summary">
                <div><dt>Total simulated</dt><dd>{boundarySimulation?.totalSimulated ?? 0}</dd></div>
                <div><dt>Department changes</dt><dd>{boundarySimulation?.changedDepartments ?? 0}</dd></div>
                <div><dt>Insurance changes</dt><dd>{boundarySimulation?.changedInsuranceStatuses ?? 0}</dd></div>
              </dl>
              {!boundarySimulation || boundarySimulation.totalSimulated === 0 || boundaryChanges.length === 0
                ? <p className="impact-empty">No boundary changes detected in the simulated range.</p>
                : <>
                  <button type="button" className="secondary impact-toggle" onClick={() => setBoundaryExpanded((expanded) => !expanded)}>
                    {boundaryExpanded ? "Hide changed ranges" : `View changed ranges (${boundaryChanges.length})`}
                  </button>
                  {boundaryExpanded && <div className="batch-table-wrap boundary-impact-table-wrap">
                    <table className="batch-outcomes-table boundary-impact-table">
                      <thead><tr><th>Changed range</th><th>Grid points</th></tr></thead>
                      <tbody>{boundaryChanges.map((range, index) => (
                        <tr key={`${range.minWeightKg}-${range.maxWeightKg}-${range.minValueEur}-${range.maxValueEur}-${index}`}>
                          <td data-label="Changed range">{boundaryRangeLabel(range)}</td>
                          <td data-label="Grid points">{range.gridPoints}</td>
                        </tr>
                      ))}</tbody>
                    </table>
                  </div>}
                </>}
            </div>
            <div className="historical-impact">
              <div className="impact-heading"><div><h3>Historical Impact</h3><p className="hint">Read-only preview of the sampled historical parcels.</p></div></div>
              <dl className="impact-summary">
                <div><dt>Parcels analyzed</dt><dd>{historicalImpact?.parcelsAnalyzed ?? 0}</dd></div>
                <div><dt>Department changes</dt><dd>{historicalImpact?.departmentChanges ?? 0}</dd></div>
                <div><dt>Insurance changes</dt><dd>{historicalImpact?.insuranceChanges ?? 0}</dd></div>
                <div><dt>Rule changes</dt><dd>{historicalImpact?.matchedRuleChanges ?? 0}</dd></div>
              </dl>
              {!historicalImpact || historicalImpact.parcelsAnalyzed === 0 ? <p className="impact-empty">No historical parcels were available for this preview.</p> : <>
                {historicalImpact.changes?.length > 0 && <button type="button" className="secondary impact-toggle" onClick={() => setImpactExpanded((expanded) => !expanded)}>{impactExpanded ? "Hide changed parcels" : `View changed parcels (${historicalImpact.changes.length})`}</button>}
                {impactExpanded && historicalImpact.changes?.length > 0 && <div className="batch-table-wrap impact-table-wrap"><table className="batch-outcomes-table impact-table"><thead><tr><th>Parcel ID</th><th>Current Department</th><th>Proposed Department</th><th>Current Rule</th><th>Proposed Rule</th></tr></thead><tbody>{historicalImpact.changes.map((change) => <tr key={change.parcelId}><td data-label="Parcel ID">#{change.parcelId ?? "—"}</td><td data-label="Current Department">{change.currentDepartment ?? "—"}</td><td data-label="Proposed Department">{change.proposedDepartment ?? "—"}</td><td data-label="Current Rule">{change.currentRule ?? "—"}</td><td data-label="Proposed Rule">{change.proposedRule ?? "—"}</td></tr>)}</tbody></table></div>}
                {historicalImpact.failures?.length > 0 && <div className="record-errors"><strong>Historical records not evaluated ({historicalImpact.failures.length})</strong><ul>{historicalImpact.failures.map((failure) => <li key={`${failure.parcelId}-${failure.error}`}>Parcel #{failure.parcelId ?? "unknown"}: {failure.error}</li>)}</ul></div>}
              </>}
            </div>
          </div>
        )}
      </section>
      {confirmActivation && ready && activeConfiguration && <ActivationConfirmModal draft={draft} activeConfiguration={activeConfiguration} changes={activationChanges} dryRun={dryRun} onCancel={() => setConfirmActivation(false)} onConfirm={activate} busy={busy === "activate"} />}
    </div>
  );
}

function HistoryPage({ credentials, onAuthInvalid }) {
  const [history, setHistory] = useState([]);
  const [toast, setToast]     = useState(null);
  const [busy, setBusy]       = useState("");

  async function refreshHistory() {
    setBusy("history");
    try { setHistory(await getConfigHistory(credentials)); }
    catch (err) { if (isAuthenticationError(err)) onAuthInvalid(); else setToast({ type: "error", message: errText(err) }); }
    finally { setBusy(""); }
  }
  useEffect(() => { refreshHistory(); }, []);

  async function rollback(version) {
    if (!window.confirm(`Rollback creates a new active version from version ${version}. Continue?`)) return;
    setBusy(`rollback-${version}`);
    try {
      const active = await rollbackConfig(version, credentials);
      setToast({ type: "success", title: "Rollback complete", message: `New active version ${active.version} created from v${version}.` });
      await refreshHistory();
    } catch (err) { if (isAuthenticationError(err)) onAuthInvalid(); else setToast({ type: "error", message: errText(err) }); }
    finally { setBusy(""); }
  }

  return (
    <div className="page-body">
      {toast && <Toast {...toast} onClose={() => setToast(null)} />}
      <section className="card">
        <SectionHeading
          title="Configuration history"
          hint="Rollback creates a new version from an archived one — it never modifies routing directly."
          action={
            <button className="secondary" onClick={refreshHistory} disabled={Boolean(busy)}>
              {busy === "history" ? "Refreshing…" : "↻ Refresh"}
            </button>
          }
        />
        <div className="history-list">
          {history.length === 0
            ? <EmptyState icon="📋" message="No configuration versions found." />
            : history.map((item) => (
              <article className="history-entry" key={item.version}>
                <div className="history-title">
                  <strong>Version {item.version}</strong>
                  <Status value={item.status} />
                </div>
                <dl>
                  <div><dt>Created by</dt><dd>{item.createdBy} · {dateText(item.createdAt)}</dd></div>
                  <div><dt>Activated by</dt><dd>{item.activatedBy ? `${item.activatedBy} · ${dateText(item.activatedAt)}` : "Not activated"}</dd></div>
                  {item.predecessorVersionId && <div><dt>Predecessor</dt><dd>Record #{item.predecessorVersionId}</dd></div>}
                  {item.reason?.trim() && <div><dt>Reason</dt><dd>{item.reason}</dd></div>}
                </dl>
                {item.status === "ARCHIVED" && (
                  <button className="secondary rollback-btn" onClick={() => rollback(item.version)} disabled={Boolean(busy)}>
                    {busy === `rollback-${item.version}` ? "Rolling back…" : "Rollback to this version"}
                  </button>
                )}
              </article>
            ))
          }
        </div>
      </section>
    </div>
  );
}

function ApproverPage({ credentials, onAuthInvalid }) {
  const [pending, setPending]         = useState([]);
  const [toast, setToast]             = useState(null);
  const [loading, setLoading]         = useState(false);
  const [approvingId, setApprovingId] = useState(null);

  async function loadPending() {
    setLoading(true);
    try { setPending(await getPendingApprovals(credentials)); }
    catch (err) { if (isAuthenticationError(err)) onAuthInvalid(); else setToast({ type: "error", message: errText(err) }); }
    finally { setLoading(false); }
  }
  useEffect(() => { loadPending(); }, []);

  async function approve(parcel) {
    setApprovingId(parcel.id); setToast(null);
    try {
      const routed = await approveParcel(parcel.id, credentials);
      setPending((items) => items.filter((i) => i.id !== parcel.id));
      setToast({ type: "success", title: `Parcel #${routed.id} approved`, message: `Routed to ${routed.department}.` });
    } catch (err) { if (isAuthenticationError(err)) onAuthInvalid(); else setToast({ type: "error", message: errText(err) }); }
    finally { setApprovingId(null); }
  }

  return (
    <div className="page-body">
      {toast && <Toast {...toast} onClose={() => setToast(null)} />}
      <section className="card">
        <SectionHeading
          title="Pending insurance approvals"
          hint="Each parcel below requires insurance approval before routing can be completed."
          action={
            <button className="secondary" onClick={loadPending} disabled={loading || approvingId !== null}>
              {loading ? "Refreshing…" : "↻ Refresh"}
            </button>
          }
        />
        {loading ? (
          <div className="loading-state"><p>Loading pending approvals…</p></div>
        ) : pending.length === 0 ? (
          <EmptyState icon="✅" message="No parcels are currently pending insurance approval." />
        ) : (
          <div className="approval-list">
            {pending.map((parcel) => (
              <article className="approval-card" key={parcel.id}>
                <div className="result-heading">
                  <div>
                    <strong>Parcel #{parcel.id}</strong>
                    <p className="insurance-flag">⚠ Insurance approval required</p>
                  </div>
                  <Status value={parcel.status} />
                </div>
                <dl className="approval-details">
                  <div><dt>Weight</dt><dd>{parcel.weightKg} kg</dd></div>
                  <div><dt>Declared value</dt><dd>EUR {parcel.valueEur}</dd></div>
                  <div><dt>Destination</dt><dd>{countryName(parcel.destinationCountry)}</dd></div>
                  <div><dt>Predicted department</dt><dd>{parcel.predictedDepartment}</dd></div>
                  <div><dt>Matched rule</dt><dd>{parcel.matchedRuleId}</dd></div>
                  <div><dt>Config version</dt><dd>{parcel.routingConfigVersionId}</dd></div>
                </dl>
                <button className="approve-btn" onClick={() => approve(parcel)} disabled={approvingId !== null}>
                  {approvingId === parcel.id ? "Approving…" : "Approve"}
                </button>
              </article>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

const NAV = {
  OPERATOR:           [{ id: "route",     label: "Route parcels", icon: "📦" }, { id: "batch",   label: "Batch upload", icon: "📂" }],
  INSURANCE_APPROVER: [{ id: "approvals", label: "Approvals",     icon: "✅" }],
  ADMIN:              [{ id: "config",    label: "Configuration", icon: "⚙️" }, { id: "history", label: "History",      icon: "📋" }],
};

const PAGE_TITLES = {
  route: "Route parcels", batch: "Batch upload",
  approvals: "Insurance approvals",
  config: "Configuration", history: "Config history",
};

export default function App() {
  const [session, setSession]       = useState(null);
  const [tab, setTab]               = useState(null);
  const contentRef                  = useRef(null);
  const [form, setForm]             = useState(configInitial);
  const [draft, setDraft]           = useState(null);
  const [validation, setValidation] = useState(null);
  const [dryRun, setDryRun]         = useState(null);
  const [toast, setToast]           = useState(null);
  const [busy, setBusy]             = useState("");

  function onAuthenticated(s) {
    setSession(s);
    setTab(NAV[s.role]?.[0]?.id ?? null);
  }

  function switchTab(id) {
    setTab(id);
    setToast(null);
    if (contentRef.current) contentRef.current.scrollTop = 0;
    else window.scrollTo({ top: 0, behavior: "instant" });
  }

  if (!session) return <LoginPage onAuthenticated={onAuthenticated} />;

  const navItems  = NAV[session.role] ?? [];
  const roleLabel = session.role.replaceAll("_", " ");
  const creds     = session.credentials;
  const invalid   = () => setSession(null);
  const adminShared = { form, setForm, draft, setDraft, validation, setValidation, dryRun, setDryRun, toast, setToast, busy, setBusy };

  function renderPage() {
    switch (tab) {
      case "route":     return <RouteParcelPage credentials={creds} onAuthInvalid={invalid} />;
      case "batch":     return <BatchUploadPage credentials={creds} onAuthInvalid={invalid} />;
      case "approvals": return <ApproverPage    credentials={creds} onAuthInvalid={invalid} />;
      case "config":    return <ConfigPage      credentials={creds} onAuthInvalid={invalid} sharedState={adminShared} />;
      case "history":   return <HistoryPage     credentials={creds} onAuthInvalid={invalid} />;
      default:          return null;
    }
  }

  return (
    <div className="app-shell">
      <aside className="app-sidebar">
        <div className="brand">
          <span className="brand-mark">PRS</span>
          <span>Parcel Routing</span>
        </div>
        <nav className="app-nav">
          {navItems.map((item) => (
            <button key={item.id} className={tab === item.id ? "active" : ""} onClick={() => switchTab(item.id)}>
              <span className="nav-icon">{item.icon}</span> {item.label}
            </button>
          ))}
        </nav>
        <div className="sidebar-footer">
          <span className="role-badge">{roleLabel}</span>
          <button className="logout-btn" onClick={() => setSession(null)}>Sign out</button>
        </div>
      </aside>

      <main className="app-content" ref={contentRef}>
        <div className="page-header">
          <p className="eyebrow">Parcel Routing System</p>
          <h1>{PAGE_TITLES[tab] ?? ""}</h1>
        </div>
        {renderPage()}
      </main>
    </div>
  );
}
