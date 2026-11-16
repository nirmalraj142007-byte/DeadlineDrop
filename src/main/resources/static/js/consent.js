/**
 * DeadlineDrop — optional measurement consent (text version v1).
 *
 * Reads the consent checkbox and network select on the submit page and, only when the box was
 * ticked, sends one anonymised telemetry record after a successful arm C finalize. The call is
 * fire-and-forget: it never blocks or delays the submission and its failure never reaches the student.
 */

import { apiFetch } from './api.js';

const CONSENT_VERSION = 'v1';

/**
 * Snapshot of the consent controls at the moment the student clicks Submit.
 *
 * @returns {{consented: boolean, networkType: string}}
 */
export function readConsent() {
  const box = document.getElementById('consent-checkbox');
  const select = document.getElementById('network-type');
  return {
    consented: Boolean(box && box.checked),
    networkType: select && select.value ? select.value : 'UNKNOWN'
  };
}

/**
 * Send the saved telemetry once, if (and only if) the student consented.
 * Returns immediately; the request runs in the background.
 *
 * @param {string} assignmentId
 * @param {{consented: boolean, networkType: string}} consent
 */
export function sendTelemetryOnce(assignmentId, consent) {
  if (!consent || !consent.consented) return;

  const sentKey = `dd.telemetry.sent.${assignmentId}`;
  if (sessionStorage.getItem(sentKey)) return;

  const raw = sessionStorage.getItem(`dd.telemetry.${assignmentId}`);
  if (!raw) return;

  let saved;
  try {
    saved = JSON.parse(raw);
  } catch (err) {
    console.warn('Saved telemetry could not be read; nothing sent.', err);
    return;
  }

  sessionStorage.setItem(sentKey, '1');
  apiFetch('/api/telemetry', {
    method: 'POST',
    body: {
      assignmentId: saved.assignmentId,
      consentVersion: CONSENT_VERSION,
      uploadMbps: saved.uploadMbps,
      sizeBytes: saved.sizeBytes,
      clickOffsetMs: Math.round(saved.clickOffsetMs),
      hashMs: saved.hashMs,
      networkType: consent.networkType
    }
  }).catch((err) => {
    // Measurement is optional; a failure here must not affect the submission.
    console.warn('Optional telemetry was not recorded.', err);
  });
}
