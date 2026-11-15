/**
 * DeadlineDrop — Arm A Submission Flow (Naive Multipart Baseline)
 *
 * Uploads the entire file through the application server as a multipart request,
 * stamping submission time only when the upload transfer finishes.
 */

import { serverNow } from '../api.js';
import { formatIst } from '../ui.js';

/**
 * Run the Arm A submission flow.
 *
 * @param {object} options
 * @param {object} options.assignment - Assignment details
 * @param {File} options.file - Selected file
 * @param {string} options.idempotencyKey - UUID idempotency key
 * @param {object} options.ui - UI updater callbacks
 */
export async function runArmAFlow({ assignment, file, idempotencyKey, ui }) {
  const clickMs = serverNow();
  const deadlineEpochMs = new Date(assignment.deadlineAt).getTime();

  ui.showProgress();
  ui.updateStatus('Uploading file through server…');
  ui.updateProgress(0);

  const token = typeof sessionStorage !== 'undefined' ? sessionStorage.getItem('token') : null;
  const formData = new FormData();
  formData.append('file', file);

  const uploadStartTime = performance.now();
  let lastMbps = 0;

  const responseJson = await new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `/api/assignments/${assignment.id}/naive-submissions`, true);

    if (token) {
      xhr.setRequestHeader('Authorization', `Bearer ${token}`);
    }
    if (idempotencyKey) {
      xhr.setRequestHeader('Idempotency-Key', idempotencyKey);
    }

    xhr.upload.onprogress = (evt) => {
      if (evt.lengthComputable && evt.total > 0) {
        const elapsedSec = (performance.now() - uploadStartTime) / 1000;
        const mbps = elapsedSec > 0 ? (evt.loaded * 8) / (1_000_000 * elapsedSec) : 0;
        const pct = Math.round((evt.loaded / evt.total) * 100);
        lastMbps = mbps;
        ui.updateProgress(pct);
        const mbpsText = mbps > 0 ? ` (${mbps.toFixed(1)} Mbps)` : '';
        ui.updateStatus(`Uploading ${pct}%${mbpsText}…`);
      }
    };

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          const data = JSON.parse(xhr.responseText);
          resolve(data);
        } catch (e) {
          reject(new Error('Invalid response received from server'));
        }
      } else {
        let errMessage = `Upload failed with status ${xhr.status}`;
        try {
          const err = JSON.parse(xhr.responseText);
          if (err.detail || err.code) {
            errMessage = err.detail || err.code;
          }
        } catch (e) {}
        reject(new Error(errMessage));
      }
    };

    xhr.onerror = () => {
      reject(new Error('Network error during multipart upload'));
    };

    xhr.send(formData);
  });

  ui.updateProgress(100);
  const uploadElapsedSec = (performance.now() - uploadStartTime) / 1000;
  const finalUploadMbps = uploadElapsedSec > 0
    ? Number(((file.size * 8) / (1_000_000 * uploadElapsedSec)).toFixed(2))
    : Number(lastMbps.toFixed(2));

  // Save telemetry in sessionStorage
  const clickOffsetMs = clickMs - deadlineEpochMs;
  const telemetryData = {
    assignmentId: assignment.id,
    clickOffsetMs,
    hashMs: 0,
    uploadMbps: finalUploadMbps,
    sizeBytes: file.size
  };
  sessionStorage.setItem(`dd.telemetry.${assignment.id}`, JSON.stringify(telemetryData));

  // Show inline result: "Submitted at <IST> · ON TIME/LATE"
  const submittedIst = formatIst(responseJson.submittedAt);
  const timingLabel = responseJson.timing === 'ON_TIME' ? 'ON TIME' : 'LATE';
  ui.showInlineResult(`Submitted at ${submittedIst} · ${timingLabel}`, responseJson.timing === 'ON_TIME' ? 'success' : 'warn');
}
