// In development, Vite proxies this relative path to the Spring service. In a
// deployed environment the UI can be served beside the API, or configured with
// VITE_API_BASE_URL by the host application.
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";

function authorizationHeader(credentials) {
  return `Basic ${btoa(`${credentials.username}:${credentials.password}`)}`;
}

async function request(path, options, credentials) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers: {
      Authorization: authorizationHeader(credentials),
      ...options.headers,
    },
  });

  const contentType = response.headers.get("content-type") ?? "";
  const body = contentType.includes("application/json")
    ? await response.json().catch(() => null)
    : await response.text().catch(() => "");
  if (!response.ok) {
    const error = new Error(
      body?.message
      ?? (typeof body === "string" && body.trim())
      ?? (response.status === 401 && "Authentication failed. Check your username and password.")
      ?? (response.status === 403 && "You do not have permission to perform this action.")
      ?? `Request failed (${response.status})`,
    );
    error.status = response.status;
    throw error;
  }
  return body;
}

export function isAuthenticationError(error) {
  return error?.status === 401 || error?.status === 403;
}

export async function authenticate(credentials) {
  try {
    await getConfigHistory(credentials);
    return "ADMIN";
  } catch (error) {
    if (error?.status !== 403) throw error;
  }

  try {
    await getPendingApprovals(credentials);
    return "INSURANCE_APPROVER";
  } catch (error) {
    if (error?.status === 403) return "OPERATOR";
    throw error;
  }
}

export function submitParcel(parcel, credentials) {
  return request(
    "/api/parcels",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(parcel),
    },
    credentials,
  );
}

export function uploadBatch(file, credentials) {
  const formData = new FormData();
  formData.append("file", file);
  return request("/api/parcels/batch", { method: "POST", body: formData }, credentials);
}

export function createConfigDraft(configuration, credentials) {
  return request(
    "/api/config/drafts",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(configuration),
    },
    credentials,
  );
}

export function validateConfigDraft(version, credentials) {
  return request(`/api/config/drafts/${version}/validate`, { method: "POST" }, credentials);
}

export function runConfigDryRun(version, credentials) {
  return request(`/api/config/drafts/${version}/dry-run`, { method: "POST" }, credentials);
}

export function activateConfigDraft(version, credentials) {
  return request(`/api/config/drafts/${version}/activate`, { method: "POST" }, credentials);
}

export function getActiveConfig(credentials) {
  return request("/api/config/active", { method: "GET" }, credentials);
}

export function getConfigHistory(credentials) {
  return request("/api/config/history", { method: "GET" }, credentials);
}

export function rollbackConfig(version, credentials) {
  return request(`/api/config/${version}/rollback`, { method: "POST" }, credentials);
}

export function getPendingApprovals(credentials) {
  return request("/api/parcels/pending-approval", { method: "GET" }, credentials);
}

export function approveParcel(id, credentials) {
  return request(`/api/parcels/${id}/approve`, { method: "POST" }, credentials);
}
