// Thin client for the scheduling API. Pages import from here instead of calling fetch directly.
// Paths are relative, so the pages keep working if the app is deployed under a context path.

export class ApiError extends Error {
  constructor(status, code, message, body) {
    super(message);
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

/** POST /publish-availability. */
export function publishAvailability(request) {
  return send('POST', 'publish-availability', request);
}

/** GET /get-available-slots/{hostIds}: open times in the viewer's timezone for viewer-local dates [from, to]. */
export function getAvailableSlots(hostIds, timezone, from, to) {
  const query = new URLSearchParams({ tz: timezone, from, to });
  return send('GET', `get-available-slots/${hostIds.join(',')}?${query}`);
}

/** POST /book-slot. Rejects with ApiError code SLOT_TAKEN / DAY_FULL / ALREADY_BOOKED / NOT_FOUND. */
export function bookSlot(request) {
  return send('POST', 'book-slot', request);
}

/** POST /api/hosts/{hostId}/bookings/reschedule: moves the user's upcoming booking to another slot. */
export function rescheduleBooking(hostId, request) {
  return send('POST', `api/hosts/${hostId}/bookings/reschedule`, request);
}

/** GET /api/hosts/{hostId}/bookings: all of the host's bookings, earliest first. */
export function getHostBookings(hostId) {
  return send('GET', `api/hosts/${hostId}/bookings`);
}

async function send(method, path, body) {
  let res;
  try {
    res = await fetch(path, {
      method,
      headers: body === undefined
        ? { Accept: 'application/json' }
        : { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'NETWORK', "Couldn't reach the server. Is it running?");
  }
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    throw new ApiError(res.status, data?.error ?? `HTTP_${res.status}`,
      data?.message ?? `Request failed with status ${res.status}`, data);
  }
  return data;
}
