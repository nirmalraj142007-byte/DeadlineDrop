/**
 * DeadlineDrop — Arm B Submission Flow (Direct-Finalize Baseline)
 *
 * Requests a signed upload URL without hashing, streams bytes directly to storage,
 * and stamps the submission timestamp upon finalization.
 */

import { apiFetch, serverNow } from '../api.js';
import { formatIst } from '../ui.js';

/**
 * Execute an XMLHttpRequest PUT directly to storage.
 *
 * @param {string} url - Target signed PUT URL
 * @param {object} headers - Storage headers
 * @param {File} file - File bytes
 * @param {Function} onProgress - Progress callback
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
          // Skip restricted headers
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

    xhr.onload = () => {
      resolve({ status: xhr.status, responseText: xhr.responseText });
    };

    xhr.onerror = () => {
      resolve({ status: 0, error: 'Network error during storage upload' });
    };

    xhr.send(file);
  });
}

/**
 * Run the Arm B submission flow.
 *
 * @param {object} options
 * @param {object} options.assignment - Assignment details
 * @param {File} options.file - Selected file
 * @param {object} options.ui - UI updater callbacks
 */
export async function runArmBFlow({ assignment, file, ui }) {
  const clickMs = serverNow();
  const deadlineEpochMs = new Date(assignment.deadlineAt).getTime();
  const fileContentType = file.type || 'application/pdf';

  ui.showProgress();
  ui.updateStatus('Requesting direct upload URL…');
  ui.updateProgress(0);

  // 1. Direct upload request
  const uploadInit = await apiFetch(`/api/assignments/${assignment.id}/direct-uploads`, {
    method: 'POST',
    body: {
      sizeBytes: file.size,
      contentType: fileContentType
    }
  });

  const uploadId = uploadInit.uploadId;
  const uploadUrl = uploadInit.uploadUrl;
  const uploadHeaders = uploadInit.uploadHeaders || {};

  // 2. PUT bytes directly to storage
  ui.updateStatus('Uploading bytes to storage…');
  const uploadStartTime = performance.now();
  let lastMbps = 0;

  const putResult = await doXhrPut(
    uploadUrl,
    uploadHeaders,
    file,
    (loaded, total, mbps, pct) => {
      lastMbps = mbps;
      ui.updateProgress(pct);
      const mbpsText = mbps > 0 ? ` (${mbps.toFixed(1)} Mbps)` : '';
      ui.updateStatus(`Uploading ${pct}%${mbpsText}…`);
    }
  );

  if (putResult.status !== 200 && putResult.status !== 204) {
    throw new Error(`Upload to storage failed with status ${putResult.status}`);
  }

  ui.updateProgress(100);
  const uploadElapsedSec = (performance.now() - uploadStartTime) / 1000;
  const finalUploadMbps = uploadElapsedSec > 0
    ? Number(((file.size * 8) / (1_000_000 * uploadElapsedSec)).toFixed(2))
    : Number(lastMbps.toFixed(2));

  // 3. Finalize upload: stamps time upon arrival
  ui.updateStatus('Finalizing submission…');
  const finalizeRes = await apiFetch(`/api/assignments/${assignment.id}/direct-uploads/${uploadId}/finalize`, {
    method: 'POST'
  });

  // 4. Save telemetry in sessionStorage
  const clickOffsetMs = clickMs - deadlineEpochMs;
  const telemetryData = {
    assignmentId: assignment.id,
    clickOffsetMs,
    hashMs: 0,
    uploadMbps: finalUploadMbps,
    sizeBytes: file.size
  };
  sessionStorage.setItem(`dd.telemetry.${assignment.id}`, JSON.stringify(telemetryData));

  // 5. Show inline result: "Submitted at <IST> · ON TIME/LATE"
  const submittedIst = formatIst(finalizeRes.submittedAt);
  const timingLabel = finalizeRes.timing === 'ON_TIME' ? 'ON TIME' : 'LATE';
  ui.showInlineResult(`Submitted at ${submittedIst} · ${timingLabel}`, finalizeRes.timing === 'ON_TIME' ? 'success' : 'warn');
}
