import { useEffect, useMemo, useRef, useState } from "react";
import {
  activateConfigDraft, createConfigDraft, getConfigHistory, rollbackConfig, runConfigDryRun,
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

  async function sendParcel(e) {
    e.preventDefault();
    setToast(null); setResult(null);
    const weightKg = Number(parcel.weightKg), valueEur = Number(parcel.valueEur);
    if (!Number.isFinite(weightKg) || weightKg < 0 || !Number.isFinite(valueEur) || valueEur < 0)
      return setToast({ type: "error", message: "Weight and declared value must be non-negative numbers." });
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
        <form onSubmit={sendParcel}>
          <div className="form-row">
            <label>Weight (kg)
              <input type="number" min="0" step="any" required value={parcel.weightKg}
                onChange={(e) => setParcel({ ...parcel, weightKg: e.target.value })} placeholder="e.g. 5.2" />
            </label>
            <label>Declared value (EUR)
              <input type="number" min="0" step="any" required value={parcel.valueEur}
                onChange={(e) => setParcel({ ...parcel, valueEur: e.target.value })} placeholder="e.g. 120" />
            </label>
          </div>
          <label>Destination country
            <select required value={parcel.destinationCountry}
              onChange={(e) => setParcel({ ...parcel, destinationCountry: e.target.value })}>
              <option value="">Select a country</option>
              {countries.map(({ code, name }) => <option key={code} value={code}>{name}</option>)}
            </select>
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
    if (!file || !/\.(json|xml)$/i.test(file.name))
      return setToast({ type: "error", message: "Choose a .json or .xml batch file." });
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
              onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
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

  const changeRule      = (i, k, v) => setForm((f) => ({ ...f, rules: f.rules.map((r, idx) => idx === i ? { ...r, [k]: v } : r) }));
  const changeRuleField = (i, field) => changeRule(i, "field", field === "attribute" ? "attribute:" : field);
  const changeAttrName  = (i, name)  => changeRule(i, "field", `attribute:${name}`);

  async function create(e) {
    e.preventDefault();
    setToast(null);
    const payload = toConfig(form);
    if (!Number.isInteger(payload.insuranceThresholdEur) || payload.insuranceThresholdEur < 0)
      return setToast({ type: "error", message: "Insurance threshold must be a non-negative whole number." });
    setBusy("create");
    try {
      const next = await createConfigDraft(payload, credentials);
      setDraft(next); setValidation(null); setDryRun(null);
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
      setDryRun(next);
      setToast(next.failedCases === 0
        ? { type: "success", title: "Dry-run passed", message: "All regression cases passed. You can now activate." }
        : { type: "error",   title: "Dry-run failed",  message: `${next.failedCases} case(s) failed. Activation is blocked.` });
    } catch (err) { if (isAuthenticationError(err)) onAuthInvalid(); else setToast({ type: "error", message: errText(err) }); }
    finally { setBusy(""); }
  }

  async function activate() {
    if (!window.confirm(`Activate draft version ${draft.version}? This replaces the live configuration.`)) return;
    setBusy("activate");
    try {
      const active = await activateConfigDraft(draft.version, credentials);
      setToast({ type: "success", title: `Version ${active.version} is now ACTIVE`, message: "Live routing is using this configuration." });
      setDraft(null); setValidation(null); setDryRun(null);
    } catch (err) { if (isAuthenticationError(err)) onAuthInvalid(); else setToast({ type: "error", message: errText(err) }); }
    finally { setBusy(""); }
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

        <form onSubmit={create}>
          <fieldset disabled={Boolean(draft) || Boolean(busy)} style={{ border: "none", margin: 0, padding: 0 }}>
            <label>Insurance approval threshold (EUR)
              <input type="number" min="0" step="1" required value={form.insuranceThresholdEur}
                onChange={(e) => setForm({ ...form, insuranceThresholdEur: e.target.value })} />
              <span className="field-help">Parcels declared above this value require insurance approval before routing.</span>
            </label>
            <div className="rule-list">
              <div className="rule-list-heading">
                <div>
                  <h3>Routing rules</h3>
                  <p className="hint">Rules are evaluated in ascending priority order (lower = higher precedence).</p>
                </div>
                <button className="secondary" type="button" onClick={() => setForm({ ...form, rules: [...form.rules, { ...blankRule }] })}>+ Add rule</button>
              </div>
              {form.rules.map((rule, idx) => {
                const isAttr   = rule.field.startsWith("attribute:");
                const selField = isAttr ? "attribute" : rule.field;
                return (
                  <fieldset className="rule" key={idx}>
                    <legend>Rule {idx + 1}</legend>
                    <div className="rule-fields">
                      <label>Rule ID
                        <input required value={rule.id} onChange={(e) => changeRule(idx, "id", e.target.value)} placeholder="e.g. heavy-department" />
                        <span className="field-help">Unique, stable identifier.</span>
                      </label>
                      <label>Priority
                        <input required type="number" min="1" value={rule.priority} onChange={(e) => changeRule(idx, "priority", e.target.value)} placeholder="e.g. 10" />
                        <span className="field-help">Lower = higher precedence.</span>
                      </label>
                      <label>Department
                        <select required value={rule.department} onChange={(e) => changeRule(idx, "department", e.target.value)}>
                          <option value="" disabled>Select department</option>
                          {departments.map((d) => <option key={d} value={d}>{d}</option>)}
                        </select>
                      </label>
                      <label>Condition field
                        <select value={selField} onChange={(e) => changeRuleField(idx, e.target.value)}>
                          {conditionFields.map((f) => <option key={f.value} value={f.value}>{f.label}</option>)}
                        </select>
                      </label>
                      {isAttr && (
                        <label>Attribute name
                          <input required value={rule.field.slice("attribute:".length)} onChange={(e) => changeAttrName(idx, e.target.value)} placeholder="e.g. fragile" />
                          <span className="field-help">Matches a named parcel attribute.</span>
                        </label>
                      )}
                      <label>Operator
                        <select value={rule.operator} onChange={(e) => changeRule(idx, "operator", e.target.value)}>
                          {operatorOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                        </select>
                      </label>
                      <label>Comparison value
                        {rule.field === "destination_country" ? (
                          rule.operator === "IN" ? (
                            <select required multiple size="5"
                              value={rule.value.split(",").map((value) => value.trim()).filter(Boolean)}
                              onChange={(e) => changeRule(idx, "value", [...e.target.selectedOptions].map((option) => option.value).join(","))}>
                              {countries.map(({ code, name }) => <option key={code} value={code}>{name}</option>)}
                            </select>
                          ) : (
                            <select required value={rule.value.split(",")[0].trim()}
                              onChange={(e) => changeRule(idx, "value", e.target.value)}>
                              <option value="">Select a country</option>
                              {countries.map(({ code, name }) => <option key={code} value={code}>{name}</option>)}
                            </select>
                          )
                        ) : (
                          <input required value={rule.value} onChange={(e) => changeRule(idx, "value", e.target.value)}
                            placeholder={rule.operator === "IN" ? "Comma-separated values" : "Enter a value"} />
                        )}
                        <span className="field-help">Must be compatible with the selected field.</span>
                      </label>
                    </div>
                    <div className="rule-footer">
                      <button className="danger-link" type="button" disabled={form.rules.length === 1}
                        onClick={() => setForm({ ...form, rules: form.rules.filter((_, i) => i !== idx) })}>
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
            : <button type="button" className="secondary" onClick={() => { setDraft(null); setValidation(null); setDryRun(null); }}>Start new draft</button>
          }
        </form>

        {draft && (
          <div className="action-row">
            <button onClick={validate} disabled={Boolean(busy)}>{busy === "validate" ? "Validating…" : "Validate"}</button>
            <button onClick={dryRunDraft} disabled={Boolean(busy) || !validation?.valid}>{busy === "dryrun" ? "Running dry-run…" : "Run dry-run"}</button>
            {ready && <button className="activate" onClick={activate} disabled={Boolean(busy)}>{busy === "activate" ? "Activating…" : "Activate"}</button>}
          </div>
        )}

        {validation && (
          <div className={`result ${validation.valid ? "success" : "error"}`}>
            <strong>{validation.valid ? "✓ Validation passed" : "✕ Validation errors"}</strong>
            <p className="hint" style={{ marginTop: 6 }}>{validation.valid ? "The configuration is structurally valid." : "Correct the issues below before proceeding."}</p>
            {validation.errors?.length > 0 && <ul style={{ marginTop: 8, paddingLeft: 18 }}>{validation.errors.map((e) => <li key={e} style={{ fontSize: ".83rem" }}>{e}</li>)}</ul>}
          </div>
        )}

        {dryRun && (
          <div className={`result ${dryRun.failedCases ? "error" : "success"}`}>
            <strong>{dryRun.failedCases === 0 ? "✓ Dry-run passed" : "✕ Dry-run failed"}</strong>
            <p className="hint" style={{ marginTop: 6 }}>This simulation does not affect live routing.</p>
            <dl className="batch-summary" style={{ marginTop: 14 }}>
              <div><dt>Total cases</dt><dd>{dryRun.totalCases}</dd></div>
              <div><dt>Passed</dt><dd>{dryRun.passedCases}</dd></div>
              <div><dt>Failed</dt><dd>{dryRun.failedCases}</dd></div>
            </dl>
            {dryRun.failures?.length > 0 && (
              <div className="record-errors">
                <strong>Failure details</strong>
                <ul>
                  {dryRun.failures.map((f) => (
                    <li key={f.caseName}>
                      <strong>{f.caseName}</strong>
                      {f.error ? `: ${f.error}` : <><br />Expected: {JSON.stringify(f.expected)}<br />Actual: {JSON.stringify(f.actual)}</>}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}
      </section>
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
