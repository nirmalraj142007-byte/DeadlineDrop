/**
 * DeadlineDrop API Client & Utilities
 *
 * Plain ES module for HTTP communication, clock synchronisation, and retries.
 */

export const NETWORK_MESSAGE = "Can't reach DeadlineDrop. Check your connection; we'll retry.";

export class ApiError extends Error {
  constructor(status, code, detail, requestId, errors = []) {
    super(detail || code || `HTTP ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.detail = detail;
    this.requestId = requestId;
    this.errors = Array.isArray(errors) ? errors : [];
  }
}

/**
 * Perform an authenticated API request.
 *
 * @param {string} path - Target URL path
 * @param {object} options - Request configuration (method, body, headers, idempotencyKey,
 *   meta: object filled with status and etag, responseType: 'blob' to return a Blob)
 * @returns {Promise<any>} Parsed response data
 */
export async function apiFetch(path, { method = 'GET', body, headers = {}, idempotencyKey, meta, responseType } = {}) {
  // We store the bearer token in sessionStorage because it dies automatically when the tab is closed,
  // preventing persistence across browser sessions. The residual XSS risk is handled by the CSP in phase 29.
  const reqHeaders = { ...headers };
  const token = typeof sessionStorage !== 'undefined' ? sessionStorage.getItem('token') : null;
  if (token && !reqHeaders['Authorization']) {
    reqHeaders['Authorization'] = `Bearer ${token}`;
  }
  if (idempotencyKey) {
    reqHeaders['Idempotency-Key'] = idempotencyKey;
  }

  let reqBody = body;
  if (body !== undefined && body !== null) {
    if (typeof body === 'object' && !(body instanceof FormData) && !(body instanceof Blob)) {
      reqBody = JSON.stringify(body);
      if (!reqHeaders['Content-Type']) {
        reqHeaders['Content-Type'] = 'application/json';
      }
    } else if (typeof body === 'string' && !reqHeaders['Content-Type']) {
      reqHeaders['Content-Type'] = 'application/json';
    }
  }

  let res;
  try {
    res = await fetch(path, {
      method,
      headers: reqHeaders,
      body: reqBody
    });
  } catch (networkError) {
    // fetch rejects with a TypeError ("Failed to fetch") when the server is unreachable.
    // Status 0 keeps retry() and the submit flows treating it as retryable.
    throw new ApiError(0, 'NETWORK_ERROR', NETWORK_MESSAGE);
  }

  if (meta) {
    // Lets callers read the ETag and see a 304 without apiFetch turning it into an error.
    meta.status = res.status;
    meta.etag = res.headers.get('etag');
  }
  if (res.status === 304 && meta) {
    return null;
  }

  const contentType = res.headers.get('content-type') || '';
  let errJson = null;
  if (!res.ok && (contentType.includes('application/problem+json') || contentType.includes('application/json'))) {
    try {
      errJson = await res.json();
    } catch (e) {}
  }

  if (res.status === 401) {
    // If invalid credentials during login, let the caller handle the error without redirecting
    if (errJson && errJson.code === 'INVALID_CREDENTIALS') {
      throw new ApiError(
        401,
        'INVALID_CREDENTIALS',
        errJson.detail || 'Wrong email or password',
        errJson.requestId || res.headers.get('x-request-id') || null,
        errJson.errors
      );
    }

    if (typeof sessionStorage !== 'undefined') {
      sessionStorage.removeItem('token');
    }
    if (typeof window !== 'undefined') {
      window.location.href = '/?expired=1';
    }
    const requestId = (errJson && errJson.requestId) || res.headers.get('x-request-id') || null;
      throw new ApiError(401, 'UNAUTHENTICATED', 'Your session expired, sign in again', requestId);
  }

  if (!res.ok) {
    if (errJson) {
      throw new ApiError(
        errJson.status || res.status,
        errJson.code || 'UNKNOWN_ERROR',
        errJson.detail || errJson.message,
        errJson.requestId || res.headers.get('x-request-id') || null,
        errJson.errors
      );
    }
    const text = await res.text();
    throw new ApiError(
      res.status,
      'UNKNOWN_ERROR',
      text || res.statusText,
      res.headers.get('x-request-id') || null
    );
  }

  if (responseType === 'blob') {
    return await res.blob();
  }
  if (res.status === 204 || res.headers.get('content-length') === '0') {
    return null;
  }
  if (contentType.includes('application/json')) {
    return await res.json();
  }
  return await res.text();
}

/**
 * Execute an async operation with full-jitter exponential backoff.
 * Retries on network errors, 429 and 5xx only.
 *
 * Comment: identical to tools/k6/lib/flows.js on purpose.
 *
 * @param {Function} fn - Async operation returning a promise
 * @param {object} options - { untilEpochMs, onWait }
 * @returns {Promise<any>}
 */
export async function retry(fn, { untilEpochMs, onWait } = {}) {
  let attempt = 0;
  while (true) {
    if (untilEpochMs && Date.now() >= untilEpochMs) {
      throw new Error('deadline_grace_expired_before_attempt');
    }
    try {
      return await fn();
    } catch (err) {
      const status = err && typeof err.status === 'number' ? err.status : 0;
      const isNetworkError = (err instanceof TypeError) || status === 0;
      const isRetryableStatus = status === 429 || (status >= 500 && status <= 599);
      if (!isNetworkError && !isRetryableStatus) {
        throw err;
      }

      const maxWait = Math.min(8000, 500 * Math.pow(2, attempt));
      const jitterMs = Math.random() * maxWait;
      attempt++;

      if (untilEpochMs && Date.now() + jitterMs >= untilEpochMs) {
        throw err;
      }

      if (typeof onWait === 'function') {
        onWait(jitterMs, attempt);
      }

      await new Promise(resolve => setTimeout(resolve, jitterMs));
    }
  }
}

let clockOffset = 0;
let clockSynced = false;

/**
 * Synchronise local clock against server via three /api/time samples,
 * retaining the offset from the lowest-RTT sample.
 *
 * @returns {Promise<number>} Clock offset in milliseconds
 */
export async function syncClock() {
  const samples = [];
  for (let i = 0; i < 3; i++) {
    const t0 = Date.now();
    try {
      const res = await fetch('/api/time', { cache: 'no-store' });
      const t1 = Date.now();
      if (res.ok) {
        const data = await res.json();
        const rtt = t1 - t0;
        const midpoint = (t0 + t1) / 2;
        const offset = data.epochMs - midpoint;
        samples.push({ rtt, offset });
      }
    } catch (e) {
      // Ignore individual sample failure
    }
  }
  if (samples.length > 0) {
    samples.sort((a, b) => a.rtt - b.rtt);
    clockOffset = Math.round(samples[0].offset);
    clockSynced = true;
  }
  return clockOffset;
}

/**
 * Current server timestamp in epoch milliseconds.
 *
 * @returns {number} Estimated server epoch milliseconds
 */
export function serverNow() {
  return Date.now() + clockOffset;
}

/**
 * True once at least one /api/time sample has arrived. Until then serverNow() is the local clock,
 * so countdowns say "syncing clock…" instead of showing a time that could be off.
 *
 * @returns {boolean}
 */
export function isClockSynced() {
  return clockSynced;
}

// Sync in the background so pages render at once; never block module evaluation on /api/time.
// If every sample fails, try again every 5 s until one succeeds. The server stamp (committed_at)
// is taken on arrival and does not depend on this offset.
function syncInBackground() {
  syncClock().catch(() => {}).then(() => {
    if (!clockSynced) setTimeout(syncInBackground, 5000);
  });
}
syncInBackground();
