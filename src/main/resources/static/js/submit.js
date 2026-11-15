/**
 * DeadlineDrop — Student Assignment Submission Controller
 *
 * Configures the submission view based on server configuration (/api/config)
 * and assignment constraints, validating files before dispatching to the active arm flow.
 */

import { apiFetch } from './api.js';
import { renderState, formatIst, countdown, chip } from './ui.js';
import { runArmCFlow } from './flows/arm-c.js';
import { runArmBFlow } from './flows/arm-b.js';
import { runArmAFlow } from './flows/arm-a.js';

let currentAssignment = null;
let currentConfig = null;
let stopCountdown = null;

function formatBytes(bytes) {
  if (!bytes || bytes <= 0) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  const mb = kb / 1024;
  return `${mb.toFixed(1)} MB`;
}

function isTypeAllowed(file, allowedTypes) {
  if (!allowedTypes || allowedTypes.length === 0) return true;
  const mime = (file.type || '').toLowerCase().trim();
  const cleanMime = mime.split(';')[0].trim();
  const name = file.name.toLowerCase();

  for (const allowed of allowedTypes) {
    const a = allowed.toLowerCase().trim();
    if (cleanMime && (cleanMime === a || cleanMime.startsWith(a.replace('*', '')))) {
      return true;
    }
    if (a === 'application/pdf' && name.endsWith('.pdf')) return true;
    if (a === 'application/zip' && (name.endsWith('.zip') || name.endsWith('.jar'))) return true;
    if (a === 'image/png' && name.endsWith('.png')) return true;
    if (a === 'image/jpeg' && (name.endsWith('.jpg') || name.endsWith('.jpeg'))) return true;
    if (a === 'text/plain' && name.endsWith('.txt')) return true;
  }
  return false;
}

function getIdempotencyKey(assignmentId, file) {
  const storageKey = `dd.idempotency.${assignmentId}.${file.name}.${file.size}.${file.lastModified}`;
  let key = sessionStorage.getItem(storageKey);
  if (!key) {
    key = crypto.randomUUID();
    sessionStorage.setItem(storageKey, key);
  }
  return key;
}

export async function initSubmit() {
  const stateContainer = document.getElementById('state-container');
  const submitCard = document.getElementById('submit-card');
  const armLabelEl = document.getElementById('arm-label');
  const assignmentTitle = document.getElementById('assignment-title');
  const metaDeadline = document.getElementById('meta-deadline');
  const metaCountdown = document.getElementById('meta-countdown');
  const metaGrace = document.getElementById('meta-grace');
  const metaMaxSize = document.getElementById('meta-max-size');
  const metaAllowedTypes = document.getElementById('meta-allowed-types');
  const fileInput = document.getElementById('file-input');
  const fileError = document.getElementById('file-error');
  const fileInfo = document.getElementById('file-info');
  const submitBtn = document.getElementById('submit-btn');
  const submitForm = document.getElementById('submit-form');
  const progressContainer = document.getElementById('progress-container');
  const progressStatus = document.getElementById('progress-status');
  const progressBar = document.getElementById('progress-bar');
  const resultPanel = document.getElementById('result-panel');

  const params = new URLSearchParams(window.location.search);
  const assignmentId = params.get('assignment');

  if (!assignmentId) {
    submitCard.classList.add('visually-hidden');
    renderState(stateContainer, {
      state: 'error',
      title: 'No assignment specified',
      message: 'Please select an assignment from your assignments list.'
    });
    return;
  }

  // Load config and assignment concurrently
  renderState(stateContainer, { state: 'loading', title: 'Loading assignment…' });
  submitCard.classList.add('visually-hidden');

  try {
    const [config, assignment] = await Promise.all([
      apiFetch('/api/config'),
      apiFetch(`/api/assignments/${assignmentId}`)
    ]);

    currentConfig = config;
    currentAssignment = assignment;

    // Clear loading state
    stateContainer.innerHTML = '';
    submitCard.classList.remove('visually-hidden');

    // Display arm label for arms A and B in small grey text; hide for Arm C
    if (config.arm === 'A' || config.arm === 'B') {
      armLabelEl.textContent = config.armLabel || `Arm ${config.arm}`;
      armLabelEl.classList.remove('visually-hidden');
    } else {
      armLabelEl.classList.add('visually-hidden');
    }

    // Populate assignment metadata
    assignmentTitle.textContent = assignment.title;
    metaDeadline.textContent = formatIst(assignment.deadlineAt);
    metaGrace.textContent = `${assignment.graceSeconds}s (ends ${formatIst(assignment.graceEndsAt)})`;
    metaMaxSize.textContent = `${formatBytes(assignment.maxBytes)} (${assignment.maxBytes.toLocaleString()} bytes)`;
    metaAllowedTypes.textContent = assignment.allowedTypes.join(', ');

    if (stopCountdown) stopCountdown();
    stopCountdown = countdown(metaCountdown, new Date(assignment.deadlineAt).getTime());

    // File selection handler
    fileInput.addEventListener('change', () => {
      fileError.classList.add('visually-hidden');
      fileError.textContent = '';
      fileInfo.textContent = '';
      resultPanel.classList.add('visually-hidden');
      resultPanel.innerHTML = '';

      const file = fileInput.files && fileInput.files[0];
      if (!file) {
        submitBtn.disabled = true;
        return;
      }

      // Immediate size check
      if (file.size > assignment.maxBytes) {
        fileInput.value = '';
        submitBtn.disabled = true;
        fileError.textContent = `File exceeds max size (${formatBytes(file.size)} > ${formatBytes(assignment.maxBytes)}). Selection cancelled.`;
        fileError.classList.remove('visually-hidden');
        return;
      }

      // Immediate type check
      if (!isTypeAllowed(file, assignment.allowedTypes)) {
        fileInput.value = '';
        submitBtn.disabled = true;
        fileError.textContent = `File type "${file.type || file.name.split('.').pop()}" not allowed. Allowed types: ${assignment.allowedTypes.join(', ')}.`;
        fileError.classList.remove('visually-hidden');
        return;
      }

      // Valid file: generate or reuse idempotency key
      const key = getIdempotencyKey(assignment.id, file);
      fileInfo.textContent = `${file.name} · ${formatBytes(file.size)}`;
      submitBtn.disabled = false;
    });

    // Form submit handler
    submitForm.addEventListener('submit', async (e) => {
      e.preventDefault();
      const file = fileInput.files && fileInput.files[0];
      if (!file) return;

      submitBtn.disabled = true;
      fileInput.disabled = true;
      fileError.classList.add('visually-hidden');
      resultPanel.classList.add('visually-hidden');

      const idempotencyKey = getIdempotencyKey(assignment.id, file);

      const uiCallbacks = {
        showProgress: () => {
          progressContainer.classList.remove('visually-hidden');
        },
        updateStatus: (msg) => {
          progressStatus.textContent = msg;
        },
        updateProgress: (pct) => {
          progressBar.style.width = `${Math.min(100, Math.max(0, pct))}%`;
        },
        showInlineResult: (msg, kind = 'success') => {
          progressContainer.classList.add('visually-hidden');
          resultPanel.innerHTML = '';
          const bannerEl = document.createElement('div');
          bannerEl.className = `banner banner-${kind === 'success' ? 'info' : 'warn'}`;
          bannerEl.textContent = msg;
          resultPanel.appendChild(bannerEl);

          const backLink = document.createElement('a');
          backLink.href = '/student/assignments.html';
          backLink.className = 'btn';
          backLink.textContent = 'Back to assignments';
          resultPanel.appendChild(backLink);

          resultPanel.classList.remove('visually-hidden');
        },
        showResult: ({ submissionId, committedAt, timing, status }) => {
          progressContainer.classList.add('visually-hidden');
          resultPanel.innerHTML = '';

          const h3 = document.createElement('h3');
          h3.textContent = 'Submission Received';
          resultPanel.appendChild(h3);

          const statusPara = document.createElement('p');
          statusPara.appendChild(
            timing === 'ON_TIME'
              ? chip('ontime', 'On time ✓')
              : chip('late', 'Late')
          );
          resultPanel.appendChild(statusPara);

          const detailPara = document.createElement('p');
          detailPara.textContent = `Time stamped: ${formatIst(committedAt)} · Status: ${status}`;
          resultPanel.appendChild(detailPara);

          const idPara = document.createElement('p');
          idPara.className = 'text-muted';
          idPara.textContent = `Submission ID: ${submissionId}`;
          resultPanel.appendChild(idPara);

          const actions = document.createElement('div');
          const receiptLink = document.createElement('a');
          receiptLink.href = `/student/receipt.html?id=${encodeURIComponent(submissionId)}`;
          receiptLink.className = 'btn btn-primary';
          receiptLink.textContent = 'View Receipt';
          actions.appendChild(receiptLink);

          const listLink = document.createElement('a');
          listLink.href = '/student/assignments.html';
          listLink.className = 'btn';
          listLink.textContent = 'Back to assignments';
          actions.appendChild(listLink);

          resultPanel.appendChild(actions);
          resultPanel.classList.remove('visually-hidden');
        }
      };

      try {
        if (config.arm === 'A') {
          await runArmAFlow({ assignment, file, idempotencyKey, ui: uiCallbacks });
        } else if (config.arm === 'B') {
          await runArmBFlow({ assignment, file, ui: uiCallbacks });
        } else {
          await runArmCFlow({ assignment, file, idempotencyKey, ui: uiCallbacks });
        }
      } catch (err) {
        progressContainer.classList.add('visually-hidden');
        fileError.textContent = err.detail || err.message || 'Submission failed. Please try again.';
        fileError.classList.remove('visually-hidden');
        submitBtn.disabled = false;
        fileInput.disabled = false;
      }
    });

  } catch (err) {
    submitCard.classList.add('visually-hidden');
    renderState(stateContainer, {
      state: 'error',
      title: 'Could not load assignment',
      message: err.detail || err.message || 'Network error occurred.',
      retry: () => initSubmit()
    });
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initSubmit);
} else {
  initSubmit();
}
