import { useEffect, useMemo, useState } from "react";
import {
  activateConfigDraft, createConfigDraft, getConfigHistory, rollbackConfig, runConfigDryRun,
  approveParcel, getPendingApprovals, submitParcel, uploadBatch, validateConfigDraft,
} from "./api/parcelApi";

const parcelInitial = { weightKg: "", valueEur: "", destinationCountry: "" };
const blankRule = { id: "", priority: "", field: "weight_kg", operator: "GT", value: "", department: "" };
const configInitial = {
  insuranceThresholdEur: "1000",
  rules: [
    { ...blankRule, id: "heavy-department", priority: "10", value: "10", department: "Heavy" },
    { ...blankRule, id: "regular-department", priority: "20", value: "1", department: "Regular" },
    { ...blankRule, id: "mail-department", priority: "30", operator: "LTE", value: "1", department: "Mail" },
  ],
};
const operators = ["GT", "GTE", "LT", "LTE", "EQ", "NEQ", "IN"];
const errText = (error) => error instanceof Error ? error.message : "The request could not be completed.";
const dateText = (value) => value ? new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(value)) : "—";

function Status({ value }) { return <strong className={`status ${value?.toLowerCase()}`}>{value}</strong>; }

function OperatorPage({ credentials }) {
  const [parcel, setParcel] = useState(parcelInitial); const [file, setFile] = useState(null);
  const [result, setResult] = useState(null); const [batch, setBatch] = useState(null); const [error, setError] = useState(""); const [busy, setBusy] = useState("");
  const [batchPage, setBatchPage] = useState(1); const [batchSearch, setBatchSearch] = useState(""); const [batchStatus, setBatchStatus] = useState("ALL"); const [batchDepartment, setBatchDepartment] = useState("ALL");
  const batchOutcomes = batch?.createdParcels ?? [];
  const batchDepartments = useMemo(() => [...new Set(batchOutcomes.map((item) => item.department ?? item.predictedDepartment).filter(Boolean))].sort(), [batchOutcomes]);
  const filteredBatchOutcomes = useMemo(() => batchOutcomes.filter((item) => (
    String(item.id).includes(batchSearch.trim())
    && (batchStatus === "ALL" || item.status === batchStatus)
    && (batchDepartment === "ALL" || item.department === batchDepartment || (!item.department && item.predictedDepartment === batchDepartment))
  )), [batchOutcomes, batchSearch, batchStatus, batchDepartment]);
  const batchDistribution = useMemo(() => batchOutcomes.reduce((counts, item) => {
    if (item.status === "PENDING_APPROVAL") counts.pending += 1;
    else if (item.department === "Mail") counts.mail += 1;
    else if (item.department === "Regular") counts.regular += 1;
    else if (item.department === "Heavy") counts.heavy += 1;
    return counts;
  }, { mail: 0, regular: 0, heavy: 0, pending: 0 }), [batchOutcomes]);
  const batchPageCount = Math.max(1, Math.ceil(filteredBatchOutcomes.length / 20));
  const visibleBatchOutcomes = useMemo(() => filteredBatchOutcomes.slice((batchPage - 1) * 20, batchPage * 20), [filteredBatchOutcomes, batchPage]);
  async function sendParcel(event) {
    event.preventDefault(); setError(""); setResult(null); const weightKg = Number(parcel.weightKg); const valueEur = Number(parcel.valueEur);
    if (!credentials.username || !credentials.password) return setError("Enter operator credentials before submitting.");
    if (!Number.isFinite(weightKg) || weightKg < 0 || !Number.isFinite(valueEur) || valueEur < 0) return setError("Weight and declared value must be non-negative numbers.");
    setBusy("parcel"); try { setResult(await submitParcel({ weightKg, valueEur, destinationCountry: parcel.destinationCountry, attributes: {} }, credentials)); } catch (e) { setError(errText(e)); } finally { setBusy(""); }
  }
  async function sendBatch(event) {
    event.preventDefault(); setError(""); setBatch(null); setBatchPage(1); setBatchSearch(""); setBatchStatus("ALL"); setBatchDepartment("ALL");
    if (!credentials.username || !credentials.password) return setError("Enter operator credentials before uploading.");
    if (!file || !/\.(json|xml)$/i.test(file.name)) return setError("Choose a JSON or XML batch file.");
    setBusy("batch"); try { setBatch(await uploadBatch(file, credentials)); setBatchPage(1); } catch (e) { setError(errText(e)); } finally { setBusy(""); }
  }
  return <>{error && <div className="result error" role="alert">{error}</div>}<div className="workspace">
    <section className="card"><h2>Route one parcel</h2><form onSubmit={sendParcel}>
      <label>Weight (kg)<input name="weightKg" type="number" min="0" step="any" required value={parcel.weightKg} onChange={(e) => setParcel({ ...parcel, [e.target.name]: e.target.value })} /></label>
      <label>Declared value (EUR)<input name="valueEur" type="number" min="0" step="any" required value={parcel.valueEur} onChange={(e) => setParcel({ ...parcel, [e.target.name]: e.target.value })} /></label>
      <label>Destination country<input name="destinationCountry" value={parcel.destinationCountry} onChange={(e) => setParcel({ ...parcel, [e.target.name]: e.target.value })} placeholder="e.g. DE" /></label><button disabled={Boolean(busy)}>{busy === "parcel" ? "Routing…" : "Route parcel"}</button></form>
      {result && <section className="result success"><div className="result-heading"><span>Routing complete</span><Status value={result.status} /></div>{result.status === "PENDING_APPROVAL" && <p className="approval-note">Insurance approval is required before this parcel can be routed.</p>}<dl className="decision-grid"><div><dt>Department</dt><dd>{result.department ?? "Awaiting approval"}</dd></div><div><dt>Predicted department</dt><dd>{result.predictedDepartment}</dd></div><div><dt>Matched rule</dt><dd>{result.matchedRuleId}</dd></div><div><dt>Config version</dt><dd>{result.routingConfigVersionId}</dd></div><div><dt>Insurance required</dt><dd>{result.insuranceRequired ? "Yes" : "No"}</dd></div></dl></section>}
    </section>
    <section className="card"><h2>Upload batch</h2><form onSubmit={sendBatch}><label>Batch file (.json or .xml)<input type="file" accept=".json,.xml,application/json,application/xml,text/xml" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></label><p className="hint">Invalid records are reported individually; valid records continue processing.</p><button disabled={Boolean(busy)}>{busy === "batch" ? "Uploading…" : "Upload batch"}</button></form>
      {batch && <section className="result success"><strong>Batch complete</strong><dl className="batch-summary"><div><dt>Total records</dt><dd>{batch.totalRecords}</dd></div><div><dt>Successful</dt><dd>{batch.successfulRecords}</dd></div><div><dt>Failed</dt><dd>{batch.failedRecords}</dd></div></dl><p><strong>Created parcel IDs:</strong> {batch.createdParcelIds?.length ? batch.createdParcelIds.join(", ") : "None"}</p>
        {batchOutcomes.length > 0 && <div className="batch-outcomes"><div className="batch-outcomes-heading"><div><h3>Routing outcomes</h3><p className="hint">Showing {visibleBatchOutcomes.length} of {filteredBatchOutcomes.length} matching outcomes.</p></div><dl className="routing-distribution"><div><dt>Mail</dt><dd>{batchDistribution.mail}</dd></div><div><dt>Regular</dt><dd>{batchDistribution.regular}</dd></div><div><dt>Heavy</dt><dd>{batchDistribution.heavy}</dd></div><div><dt>Pending approval</dt><dd>{batchDistribution.pending}</dd></div></dl></div><div className="batch-filters"><label>Search parcel ID<input value={batchSearch} onChange={(e) => { setBatchSearch(e.target.value); setBatchPage(1); }} inputMode="numeric" placeholder="e.g. 1024" /></label><label>Status<select value={batchStatus} onChange={(e) => { setBatchStatus(e.target.value); setBatchPage(1); }}><option value="ALL">All</option><option value="ROUTED">ROUTED</option><option value="PENDING_APPROVAL">PENDING_APPROVAL</option></select></label><label>Department<select value={batchDepartment} onChange={(e) => { setBatchDepartment(e.target.value); setBatchPage(1); }}><option value="ALL">All</option>{batchDepartments.map((department) => <option key={department} value={department}>{department}</option>)}</select></label></div><div className="batch-table-wrap"><table className="batch-outcomes-table"><thead><tr><th>Parcel ID</th><th>Status</th><th>Department</th><th>Predicted Department</th><th>Matched Rule</th><th>Config Version</th><th>Insurance</th></tr></thead><tbody>{visibleBatchOutcomes.map((created) => <tr key={created.id}><td data-label="Parcel ID">#{created.id}</td><td data-label="Status"><Status value={created.status} /></td><td data-label="Department">{created.status === "PENDING_APPROVAL" ? "Awaiting approval" : created.department}</td><td data-label="Predicted Department">{created.predictedDepartment}</td><td data-label="Matched Rule">{created.matchedRuleId}</td><td data-label="Config Version">{created.routingConfigVersionId}</td><td data-label="Insurance">{created.status === "PENDING_APPROVAL" ? <span className="insurance-flag">Insurance approval required</span> : "No"}</td></tr>)}{visibleBatchOutcomes.length === 0 && <tr><td className="batch-empty" colSpan="7">No routing outcomes match these filters.</td></tr>}</tbody></table></div>{batchPageCount > 1 && <nav className="batch-pagination" aria-label="Batch routing outcome pages"><button className="secondary" onClick={() => setBatchPage((page) => Math.max(1, page - 1))} disabled={batchPage === 1}>Previous</button><div className="page-numbers">{Array.from({ length: batchPageCount }, (_, index) => index + 1).map((page) => <button className={page === batchPage ? "selected" : "secondary"} key={page} onClick={() => setBatchPage(page)} aria-current={page === batchPage ? "page" : undefined}>{page}</button>)}</div><button className="secondary" onClick={() => setBatchPage((page) => Math.min(batchPageCount, page + 1))} disabled={batchPage === batchPageCount}>Next</button></nav>}</div>}
        {batch.errors?.length > 0 && <div className="record-errors"><strong>Record errors</strong><ul>{batch.errors.map((item) => <li key={`${item.recordNumber}-${item.message}`}>Record {item.recordNumber}: {item.message}</li>)}</ul></div>}</section>}
    </section>
  </div></>;
}

function toConfig(form) {
  const convert = (rule) => { const numeric = ["weight_kg", "value_eur"].includes(rule.field); const values = rule.value.split(",").map((v) => numeric ? Number(v.trim()) : v.trim()); return rule.operator === "IN" ? values : values[0]; };
  return { insuranceThresholdEur: Number(form.insuranceThresholdEur), rules: form.rules.map((rule) => ({ id: rule.id.trim(), priority: Number(rule.priority), department: rule.department.trim(), condition: { field: rule.field.trim(), operator: rule.operator, value: convert(rule) } })) };
}

function AdminPage({ credentials }) {
  const [form, setForm] = useState(configInitial); const [draft, setDraft] = useState(null); const [validation, setValidation] = useState(null); const [dryRun, setDryRun] = useState(null); const [history, setHistory] = useState([]);
  const [error, setError] = useState(""); const [notice, setNotice] = useState(""); const [busy, setBusy] = useState("");
  const isAdmin = credentials.username.trim().toLowerCase() === "admin"; const ready = validation?.valid && dryRun?.failedCases === 0;
  async function refreshHistory() { setBusy("history"); setError(""); try { setHistory(await getConfigHistory(credentials)); } catch (e) { setError(errText(e)); } finally { setBusy(""); } }
  useEffect(() => { if (isAdmin && credentials.password) refreshHistory(); }, [isAdmin, credentials.password]);
  const changeRule = (index, name, value) => setForm((old) => ({ ...old, rules: old.rules.map((rule, i) => i === index ? { ...rule, [name]: value } : rule) }));
  async function create(event) { event.preventDefault(); setError(""); setNotice(""); const payload = toConfig(form); if (!Number.isInteger(payload.insuranceThresholdEur) || payload.insuranceThresholdEur < 0) return setError("Insurance threshold must be a non-negative whole number."); setBusy("create"); try { const next = await createConfigDraft(payload, credentials); setDraft(next); setValidation(null); setDryRun(null); setNotice(`Draft version ${next.version} was created. It is not active.`); await refreshHistory(); } catch (e) { setError(errText(e)); } finally { setBusy(""); } }
  async function validate() { setBusy("validate"); setError(""); try { const next = await validateConfigDraft(draft.version, credentials); setValidation(next); setNotice(next.valid ? "Validation passed. This configuration is still a draft, not active." : "Validation found errors."); } catch (e) { const message = errText(e); setValidation({ valid: false, errors: [message] }); setError(message); } finally { setBusy(""); } }
  async function dryRunDraft() { setBusy("dryrun"); setError(""); try { const next = await runConfigDryRun(draft.version, credentials); setDryRun(next); setNotice(next.failedCases === 0 ? "Dry-run passed. The draft remains inactive until activation." : "Dry-run completed with failures; activation is unavailable."); } catch (e) { setError(errText(e)); } finally { setBusy(""); } }
  async function activate() { if (!window.confirm(`Activate draft version ${draft.version}? This replaces the live configuration.`)) return; setBusy("activate"); setError(""); try { const active = await activateConfigDraft(draft.version, credentials); setNotice(`Version ${active.version} is now ACTIVE.`); setDraft(null); await refreshHistory(); } catch (e) { setError(errText(e)); } finally { setBusy(""); } }
  async function rollback(version) { if (!window.confirm(`Rollback creates a NEW active version from version ${version}. Continue?`)) return; setBusy(`rollback-${version}`); setError(""); try { const active = await rollbackConfig(version, credentials); setNotice(`Rollback created new active version ${active.version}.`); await refreshHistory(); } catch (e) { setError(errText(e)); } finally { setBusy(""); } }
  if (!isAdmin) return <section className="card access-note" role="alert"><h2>Admin access required</h2><p>Sign in with the <code>admin</code> account to access configuration. Invalid credentials and non-admin access are rejected by the API.</p></section>;
  return <div className="admin-layout">{error && <div className="result error" role="alert">{error}</div>}{notice && <div className="result success" role="status">{notice}</div>}
    <section className="card"><div className="section-heading"><div><h2>Create configuration draft</h2><p className="hint">Draft → Validate → Dry-run → Activate. Drafts and dry-runs never alter live routing.</p></div>{draft && <Status value={draft.status} />}</div><ol className="workflow"><li className={draft ? "done" : "current"}>Draft</li><li className={validation?.valid ? "done" : ""}>Validate</li><li className={dryRun?.failedCases === 0 ? "done" : ""}>Dry-run</li><li className={ready ? "current" : ""}>Activate</li></ol>
      <form onSubmit={create}><fieldset disabled={Boolean(draft) || Boolean(busy)}><label>Insurance threshold (EUR)<input type="number" min="0" step="1" required value={form.insuranceThresholdEur} onChange={(e) => setForm({ ...form, insuranceThresholdEur: e.target.value })} /></label><div className="rule-list"><div className="rule-list-heading"><h3>Rules</h3><button className="secondary" type="button" onClick={() => setForm({ ...form, rules: [...form.rules, { ...blankRule }] })}>Add rule</button></div>{form.rules.map((rule, index) => <fieldset className="rule" key={index}><legend>Rule {index + 1}</legend><div className="rule-fields"><label>ID<input required value={rule.id} onChange={(e) => changeRule(index, "id", e.target.value)} /></label><label>Priority<input required type="number" min="1" value={rule.priority} onChange={(e) => changeRule(index, "priority", e.target.value)} /></label><label>Field<input required value={rule.field} onChange={(e) => changeRule(index, "field", e.target.value)} placeholder="weight_kg" /></label><label>Operator<select value={rule.operator} onChange={(e) => changeRule(index, "operator", e.target.value)}>{operators.map((item) => <option key={item}>{item}</option>)}</select></label><label>Value<input required value={rule.value} onChange={(e) => changeRule(index, "value", e.target.value)} placeholder={rule.operator === "IN" ? "Comma-separated values" : "Value"} /></label><label>Department<input required value={rule.department} onChange={(e) => changeRule(index, "department", e.target.value)} /></label></div><button className="danger-link" type="button" disabled={form.rules.length === 1} onClick={() => setForm({ ...form, rules: form.rules.filter((_, i) => i !== index) })}>Remove rule</button></fieldset>)}</div></fieldset>{!draft ? <button disabled={Boolean(busy)}>{busy === "create" ? "Creating draft…" : "Create draft"}</button> : <button type="button" className="secondary" onClick={() => { setDraft(null); setValidation(null); setDryRun(null); setNotice(""); }}>Create another draft</button>}</form>
      {draft && <div className="action-row"><button onClick={validate} disabled={Boolean(busy)}>{busy === "validate" ? "Validating…" : "Validate Draft"}</button><button onClick={dryRunDraft} disabled={Boolean(busy) || !validation?.valid}>{busy === "dryrun" ? "Running dry-run…" : "Run Dry-Run"}</button>{ready && <button className="activate" onClick={activate} disabled={Boolean(busy)}>{busy === "activate" ? "Activating…" : "Activate"}</button>}</div>}
      {validation && <section className={`result ${validation.valid ? "success" : "error"}`}><strong>{validation.valid ? "Validation passed" : "Validation errors"}</strong><p>{validation.valid ? "The configuration is valid but remains inactive." : "Correct the listed issues before creating another draft."}</p>{validation.errors?.length > 0 && <ul>{validation.errors.map((item) => <li key={item}>{item}</li>)}</ul>}</section>}
      {dryRun && <section className={`result ${dryRun.failedCases ? "error" : "success"}`}><strong>Dry-run complete</strong><p className="hint">This simulation does not affect live routing.</p><dl className="batch-summary"><div><dt>Total cases</dt><dd>{dryRun.totalCases}</dd></div><div><dt>Passed</dt><dd>{dryRun.passedCases}</dd></div><div><dt>Failed</dt><dd>{dryRun.failedCases}</dd></div></dl>{dryRun.failures?.length > 0 && <div className="record-errors"><strong>Failure details</strong><ul>{dryRun.failures.map((failure) => <li key={failure.caseName}><strong>{failure.caseName}</strong>{failure.error ? `: ${failure.error}` : <><br />Expected: {JSON.stringify(failure.expected)}<br />Actual: {JSON.stringify(failure.actual)}</>}</li>)}</ul></div>}</section>}
    </section>
    <section className="card"><div className="section-heading"><div><h2>Configuration history</h2><p className="hint">Rollback creates a NEW configuration version; React never performs routing logic.</p></div><button className="secondary" onClick={refreshHistory} disabled={Boolean(busy)}>{busy === "history" ? "Refreshing…" : "Refresh"}</button></div><div className="history-list">{history.length === 0 ? <p className="hint">No configuration versions available.</p> : history.map((item) => <article className="history-entry" key={item.version}><div className="history-title"><strong>Version {item.version}</strong><Status value={item.status} /></div><dl><div><dt>Created</dt><dd>{item.createdBy} · {dateText(item.createdAt)}</dd></div><div><dt>Activated</dt><dd>{item.activatedBy ? `${item.activatedBy} · ${dateText(item.activatedAt)}` : "Not activated"}</dd></div>{item.predecessorVersionId && <div><dt>Predecessor</dt><dd>Version record #{item.predecessorVersionId}</dd></div>}</dl>{item.status === "ARCHIVED" && <button className="secondary" onClick={() => rollback(item.version)} disabled={Boolean(busy)}>{busy === `rollback-${item.version}` ? "Rolling back…" : "Rollback to this configuration"}</button>}</article>)}</div></section>
  </div>;
}

function ApproverPage({ credentials }) {
  const [pending, setPending] = useState([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [loading, setLoading] = useState(false);
  const [approvingId, setApprovingId] = useState(null);
  const isApprover = credentials.username.trim().toLowerCase() === "approver";

  async function loadPending() {
    setLoading(true); setError("");
    try { setPending(await getPendingApprovals(credentials)); }
    catch (e) { setError(errText(e)); }
    finally { setLoading(false); }
  }

  useEffect(() => { if (isApprover && credentials.password) loadPending(); }, [isApprover, credentials.password]);

  async function approve(parcel) {
    setApprovingId(parcel.id); setError(""); setNotice("");
    try {
      const routed = await approveParcel(parcel.id, credentials);
      setPending((items) => items.filter((item) => item.id !== parcel.id));
      setNotice(`Parcel ${routed.id} is ROUTED to ${routed.department}.`);
    } catch (e) { setError(errText(e)); }
    finally { setApprovingId(null); }
  }

  if (!isApprover) return <section className="card access-note" role="alert"><h2>Insurance approver access required</h2><p>Sign in with the <code>approver</code> account to review pending insurance decisions. Invalid credentials and other roles are rejected by the API.</p></section>;
  return <section className="card approval-page">
    <div className="section-heading"><div><h2>Pending insurance approvals</h2><p className="hint">Each parcel below requires insurance approval before routing can be completed.</p></div><button className="secondary" onClick={loadPending} disabled={loading || approvingId !== null}>{loading ? "Refreshing…" : "Refresh"}</button></div>
    {error && <div className="result error" role="alert">{error}</div>}
    {notice && <div className="result success" role="status">{notice}</div>}
    {loading ? <p className="loading-state" role="status">Loading pending approvals…</p> : pending.length === 0 ? <p className="empty-state">There are no pending insurance approvals.</p> : <div className="approval-list">{pending.map((parcel) => <article className="approval-card" key={parcel.id}>
      <div className="result-heading"><div><strong>Parcel #{parcel.id}</strong><p className="insurance-flag">Insurance approval required</p></div><Status value={parcel.status} /></div>
      <dl className="approval-details"><div><dt>Weight</dt><dd>{parcel.weightKg} kg</dd></div><div><dt>Declared value</dt><dd>EUR {parcel.valueEur}</dd></div><div><dt>Predicted department</dt><dd>{parcel.predictedDepartment}</dd></div><div><dt>Matched rule</dt><dd>{parcel.matchedRuleId}</dd></div><div><dt>Config version</dt><dd>{parcel.routingConfigVersionId}</dd></div><div><dt>Insurance required</dt><dd>{(parcel.insuranceRequired ?? parcel.status === "PENDING_APPROVAL") ? "Yes" : "No"}</dd></div></dl>
      <button onClick={() => approve(parcel)} disabled={approvingId !== null}>{approvingId === parcel.id ? "Approving…" : "Approve"}</button>
    </article>)}</div>}
  </section>;
}

export default function App() {
  const [credentials, setCredentials] = useState({ username: "operator", password: "" }); const [page, setPage] = useState("operator"); const admin = page === "admin"; const approver = page === "approver";
  const title = admin ? "Administration" : approver ? "Insurance approvals" : "Operator console";
  const lede = admin ? "Prepare, verify, and safely activate routing configuration." : approver ? "Review and complete insurance-required parcel routing." : "Submit a parcel or process a batch with your operator account.";
  return <main className="shell"><header><p className="eyebrow">Parcel Routing System</p><h1>{title}</h1><p className="lede">{lede}</p></header><nav className="page-tabs" aria-label="Console pages"><button className={page === "operator" ? "selected" : "secondary"} onClick={() => setPage("operator")}>Operator</button><button className={approver ? "selected" : "secondary"} onClick={() => setPage("approver")}>Insurance approvals</button><button className={admin ? "selected" : "secondary"} onClick={() => setPage("admin")}>Admin configuration</button></nav><section className="credentials card"><h2>Sign-in credentials</h2><div className="form-row"><label>Username<input value={credentials.username} onChange={(e) => setCredentials({ ...credentials, username: e.target.value })} autoComplete="username" /></label><label>Password<input type="password" value={credentials.password} onChange={(e) => setCredentials({ ...credentials, password: e.target.value })} autoComplete="current-password" /></label></div><p className="hint">Credentials are used only for requests and remain in memory for this page session. Insurance approvals require the <code>approver</code> account; configuration requires <code>admin</code>.</p></section>{admin ? <AdminPage credentials={credentials} /> : approver ? <ApproverPage credentials={credentials} /> : <OperatorPage credentials={credentials} />}</main>;
}
