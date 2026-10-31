// Thin client for the scheduling API. Pages import from here instead of calling fetch directly.

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

/** POST /publish-availability. Resolves with the response body, rejects with ApiError. */
export function publishAvailability(request) {
  return postJson('publish-availability', request);
}

async function postJson(path, body) {
  let res;
  try {
    // Relative path, so the page keeps working if the app is deployed under a context path.
    res = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'NETWORK', "Couldn't reach the server. Is it running?");
  }
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    throw new ApiError(res.status, data?.error ?? `HTTP_${res.status}`,
      data?.message ?? `Request failed with status ${res.status}`);
  }
  return data;
}
