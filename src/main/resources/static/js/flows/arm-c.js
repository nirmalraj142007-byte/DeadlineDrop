/**
 * DeadlineDrop — Arm C Submission Flow (Hash Commitment)
 *
 * Browser computes SHA-256, server stamps arrival time immediately on commit,
 * bytes stream directly to signed storage URL, followed by finalization.
 */

import { apiFetch, retry, serverNow } from '../api.js';
import { formatIst } from '../ui.js';

/**
 * Execute an XMLHttpRequest PUT directly to storage.
 *
 * @param {string} url - Target signed PUT URL
 * @param {object} headers - Headers required by storage (e.g. Content-Type)
 * @param {File} file - File bytes
 * @param {Function} onProgress - Progress callback (loaded, total, mbps, pct)
 * @returns {Promise<{status: number, responseText?: string, error?: any}>}
 */
function doXhrPut(url, headers, file, onProgress) {
  return new Promise((resolve) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url, true);

    if (headers) {
      for (const [key, value] of Object.entries(headers)) {
        try {
          xhr.setRequestHeader(key, value);
        } catch (e) {
          // Skip restricted or invalid headers
        }
      }
    }
    if (!headers || !headers['Content-Type']) {
      xhr.setRequestHeader('Content-Type', file.type || 'application/pdf');
    }

    const startTime = performance.now();
    xhr.upload.onprogress = (evt) => {
      if (evt.lengthComputable && evt.total > 0) {
        const elapsedSec = (performance.now() - startTime) / 1000;
        const mbps = elapsedSec > 0 ? (evt.loaded * 8) / (1_000_000 * elapsedSec) : 0;
        const pct = Math.round((evt.loaded / evt.total) * 100);
        onProgress(evt.loaded, evt.total, mbps, pct);
      }
    };

    let settled = false;
    const cleanup = () => {
      if (typeof window !== 'undefined' && offlineListener) {
        window.removeEventListener('offline', offlineListener);
      }
    };

    const offlineListener = () => {
      if (!settled) {
        try {
          xhr.abort();
        } catch (e) {}
      }
    };
    if (typeof window !== 'undefined') {
      window.addEventListener('offline', offlineListener);
    }

    xhr.onload = () => {
      if (settled) return;
      settled = true;
      cleanup();
      resolve({ status: xhr.status, responseText: xhr.responseText });
    };

    xhr.onerror = () => {
      if (settled) return;
      settled = true;
      cleanup();
      resolve({ status: 0, error: 'Network error during storage upload' });
    };

    xhr.onabort = () => {
      if (settled) return;
      settled = true;
      cleanup();
      resolve({ status: 0, error: 'Connection lost (offline)' });
    };

    xhr.ontimeout = () => {
      if (settled) return;
      settled = true;
      cleanup();
      resolve({ status: 0, error: 'Storage upload timed out' });
    };

    try {
      xhr.send(file);
    } catch (e) {
      if (!settled) {
        settled = true;
        cleanup();
        resolve({ status: 0, error: e.message });
      }
    }
  });
}

/**
 * Run the Arm C submission flow.
 *
 * @param {object} options
 * @param {object} options.assignment - Assignment details
 * @param {File} options.file - Selected file
 * @param {string} options.idempotencyKey - UUID idempotency key
 * @param {object} options.ui - UI updater callbacks
 */
export async function runArmCFlow({ assignment, file, idempotencyKey, ui }) {
  const clickMs = serverNow();
  const deadlineEpochMs = new Date(assignment.deadlineAt).getTime();
  const graceEndsAtMs = new Date(assignment.graceEndsAt).getTime();
  const fileContentType = file.type || 'application/pdf';

  // Step 1: Read file with FileReader and compute SHA-256
  ui.showProgress();
  ui.updateStatus('Fingerprinting your file… 0%');
  ui.updateProgress(0);

  const hashStart = performance.now();
  const arrayBuffer = await new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onprogress = (evt) => {
      if (evt.lengthComputable && evt.total > 0) {
        const pct = Math.round((evt.loaded / evt.total) * 100);
        ui.updateStatus(`Fingerprinting your file… ${pct}%`);
        ui.updateProgress(pct);
      }
    };
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(new Error('Failed to read file for fingerprinting'));
    reader.readAsArrayBuffer(file);
  });

  const hashBuffer = await crypto.subtle.digest('SHA-256', arrayBuffer);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  const fileSha256 = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
  const hashMs = Math.round(performance.now() - hashStart);

  // Step 2: Lock submission time on arrival
  ui.updateStatus('Locking your submission time… (not yet recorded)');

  const commitRes = await retry(async () => {
    return await apiFetch(`/api/assignments/${assignment.id}/commits`, {
      method: 'POST',
      idempotencyKey,
      body: {
        sha256: fileSha256,
        sizeBytes: file.size,
        contentType: fileContentType
      }
    });
  }, { untilEpochMs: graceEndsAtMs });

  const submissionId = commitRes.submissionId;
  const committedAt = commitRes.committedAt;
  const committedIst = formatIst(committedAt);
  ui.updateStatus(`Time locked ✓ ${committedIst}`);

  let uploadUrl = commitRes.uploadUrl;
  let uploadHeaders = commitRes.uploadHeaders || {};

  // Step 3: If uploadUrl is null, call /upload-url
  if (!uploadUrl) {
    ui.updateStatus('Requesting signed upload URL…');
    const reissueRes = await retry(async () => {
      return await apiFetch(`/api/submissions/${submissionId}/upload-url`, {
        method: 'POST',
        body: { sha256: fileSha256 }
      });
    }, { untilEpochMs: graceEndsAtMs });
    uploadUrl = reissueRes.uploadUrl;
    uploadHeaders = reissueRes.uploadHeaders || {};
  }

  // Step 4: Direct upload to storage with progress and retries
  let currentUploadUrl = uploadUrl;
  let currentUploadHeaders = uploadHeaders;
  let uploadAttempt = 0;
  let lastMbps = 0;
  const uploadStartTime = performance.now();

  while (true) {
    if (Date.now() >= graceEndsAtMs) {
      throw new Error('Upload grace window expired before upload finished');
    }
    uploadAttempt++;

    const putResult = await doXhrPut(
      currentUploadUrl,
      currentUploadHeaders,
      file,
      (loaded, total, mbps, pct) => {
        lastMbps = mbps;
        ui.updateProgress(pct);
        const mbpsText = mbps > 0 ? ` (${mbps.toFixed(1)} Mbps)` : '';
        ui.updateStatus(
          `Time locked ✓ ${committedIst} · Upload ${pct}%${mbpsText} · keep this tab open until the upload finishes; grace ends ${formatIst(assignment.graceEndsAt)}`
        );
      }
    );

    if (putResult.status === 200 || putResult.status === 204) {
      ui.updateProgress(100);
      break;
    }

    if (putResult.status === 403) {
      // Storage URL expired or rejected: re-issue URL and retry
      ui.updateStatus('Storage URL expired, requesting fresh signed URL…');
      const reissue = await apiFetch(`/api/submissions/${submissionId}/upload-url`, {
        method: 'POST',
        body: { sha256: fileSha256 }
      });
      currentUploadUrl = reissue.uploadUrl;
      currentUploadHeaders = reissue.uploadHeaders || {};
      continue;
    }

    const status = putResult.status;
    const isNetwork = status === 0;
    const isRetryable = isNetwork || (status >= 500 && status <= 599) || status === 429;
    if (!isRetryable) {
      throw new Error(`Upload failed with status ${status}`);
    }

    const maxWait = Math.min(8000, 500 * Math.pow(2, uploadAttempt - 1));
    const jitterMs = Math.random() * maxWait;
    if (Date.now() + jitterMs >= graceEndsAtMs) {
      throw new Error('Upload grace window expired during retry backoff');
    }

    const waitSec = Math.max(1, Math.round(jitterMs / 1000));
    const errDetail = isNetwork
      ? (navigator.onLine ? 'storage unreachable' : 'offline')
      : `HTTP ${status}`;
    ui.updateStatus(`Connection lost (${errDetail}), retrying upload in ${waitSec}s…`);
    await new Promise(r => setTimeout(r, jitterMs));

    while (typeof navigator !== 'undefined' && !navigator.onLine) {
      ui.updateStatus('Connection lost (offline), waiting for network to reconnect…');
      await new Promise(r => setTimeout(r, 1000));
      if (Date.now() >= graceEndsAtMs) {
        throw new Error('Upload grace window expired while offline');
      }
    }
  }

  const uploadElapsedSec = (performance.now() - uploadStartTime) / 1000;
  const finalUploadMbps = uploadElapsedSec > 0
    ? Number(((file.size * 8) / (1_000_000 * uploadElapsedSec)).toFixed(2))
    : Number(lastMbps.toFixed(2));

  // Step 5: Finalize submission (BYTES_NOT_FOUND is retryable up to 5 times)
  ui.updateStatus('Confirming submission bytes in storage…');
  let finalizeAttempts = 0;
  let bytesNotFoundCount = 0;
  let finalizeRes = null;

  while (true) {
    if (Date.now() >= graceEndsAtMs) {
      throw new Error('Grace window expired before finalize completed');
    }
    finalizeAttempts++;

    try {
      finalizeRes = await apiFetch(`/api/submissions/${submissionId}/finalize`, {
        method: 'POST'
      });
      break;
    } catch (err) {
      if (err && err.code === 'BYTES_NOT_FOUND') {
        bytesNotFoundCount++;
        if (bytesNotFoundCount > 5) {
          throw err;
        }
        ui.updateStatus(`Waiting for bytes to settle in storage (attempt ${bytesNotFoundCount}/5)…`);
        const waitMs = Math.min(2000, 400 * Math.pow(1.5, bytesNotFoundCount));
        await new Promise(r => setTimeout(r, waitMs));
        continue;
      }

      const status = err && err.status ? err.status : 0;
      const isRetryable = status === 0 || status === 429 || (status >= 500 && status <= 599);
      if (!isRetryable) {
        throw err;
      }

      const maxWait = Math.min(8000, 500 * Math.pow(2, finalizeAttempts - 1));
      const jitterMs = Math.random() * maxWait;
      if (Date.now() + jitterMs >= graceEndsAtMs) {
        throw err;
      }
      ui.updateStatus(`Finalize temporary error, retrying in ${Math.round(jitterMs / 1000)}s…`);
      await new Promise(r => setTimeout(r, jitterMs));
    }
  }

  // Step 6: Save telemetry in sessionStorage for phase 24
  const clickOffsetMs = clickMs - deadlineEpochMs;
  const telemetryData = {
    assignmentId: assignment.id,
    clickOffsetMs,
    hashMs,
    uploadMbps: finalUploadMbps,
    sizeBytes: file.size
  };
  sessionStorage.setItem(`dd.telemetry.${assignment.id}`, JSON.stringify(telemetryData));

  // Step 7: Transition to receipt or show success panel
  ui.showResult({
    submissionId,
    committedAt,
    timing: finalizeRes ? finalizeRes.timing : commitRes.timing,
    status: finalizeRes ? finalizeRes.status : 'VERIFIED',
    assignmentId: assignment.id
  });
}
