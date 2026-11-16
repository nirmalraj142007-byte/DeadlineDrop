/**
 * DeadlineDrop — Submission receipt.
 *
 * Shows the server-stamped timeline (time locked, file arrived, verified), the ON TIME / LATE
 * verdict, the SHA-256 of the committed bytes, and the audit events. Polls every 5 s until the
 * submission reaches a terminal status, then stops. Also hosts the once-per-assignment survey.
 */

import { apiFetch } from './api.js';
import { renderState, formatIst, chip, banner } from './ui.js';

const POLL_MS = 5000;
const TERMINAL = new Set(['VERIFIED', 'EXPIRED', 'REJECTED_HASH_MISMATCH']);

const NETWORKS = [
  ['HOSTEL_WIFI', 'Hostel Wi-Fi'],
  ['MOBILE_DATA', 'Mobile data'],
  ['HOME_BROADBAND', 'Home broadband'],
  ['CAMPUS_LAN', 'Campus LAN'],
  ['OTHER', 'Other']
];

const stateContainer = document.getElementById('state-container');
const receiptRoot = document.getElementById('receipt-root');
const surveyRoot = document.getElementById('survey-root');

let pollTimer = null;
let surveyShownFor = null;

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function timelineStep(label, instant, waitingText) {
  const li = el('li', instant ? 'timeline-item' : 'timeline-item timeline-pending');
  li.appendChild(el('strong', '', label));
  li.appendChild(el('div', instant ? '' : 'text-muted', instant ? formatIst(instant) : waitingText));
  return li;
}

function renderCopyRow(sha256) {
  const row = el('div', 'hash-row');
  row.appendChild(el('code', 'mono', sha256));
  const btn = el('button', 'btn', 'Copy');
  btn.type = 'button';
  btn.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(sha256);
      btn.textContent = 'Copied';
    } catch (err) {
      console.warn('Clipboard write was refused.', err);
      btn.textContent = 'Copy failed';
    }
  });
  row.appendChild(btn);
  return row;
}

function renderReceipt(r) {
  receiptRoot.innerHTML = '';
  const card = el('section', 'card');
  card.appendChild(el('h2', '', r.assignmentTitle));

  const verdict = el('p', 'verdict-big');
  verdict.appendChild(r.timing === 'ON_TIME'
    ? chip('ontime', 'ON TIME')
    : r.timing === 'LATE' ? chip('late', 'LATE') : chip('pending', 'TIMING PENDING'));
  if (r.status === 'EXPIRED' || r.status === 'REJECTED_HASH_MISMATCH') {
    // The time lock stays as recorded, but no file was accepted, so say so in words.
    verdict.appendChild(document.createTextNode(' '));
    verdict.appendChild(chip('late', 'NOT ACCEPTED'));
  }
  card.appendChild(verdict);

  if (r.status === 'EXPIRED') {
    card.appendChild(banner('error', 'Your file never arrived before grace ended'));
  } else if (r.status === 'REJECTED_HASH_MISMATCH') {
    card.appendChild(banner('error', 'The file that arrived is not the file you committed'));
  } else if (r.status !== 'VERIFIED' && r.graceEndsAt) {
    card.appendChild(banner('info', `Grace ends at ${formatIst(r.graceEndsAt)}`));
  }

  const steps = el('ol', 'timeline');
  steps.appendChild(timelineStep('Time locked (server clock)', r.committedAt, 'Waiting'));
  steps.appendChild(timelineStep('File arrived', r.uploadedAt, 'Waiting for your file'));
  steps.appendChild(timelineStep('Verified', r.verifiedAt, 'Waiting for verification'));
  card.appendChild(steps);

  card.appendChild(el('h3', '', 'SHA-256 (your hash commitment)'));
  card.appendChild(renderCopyRow(r.sha256));
  card.appendChild(el('p', 'text-muted',
    `${formatBytes(r.sizeBytes)} (${r.sizeBytes.toLocaleString()} bytes) · ${r.contentType}`));

  const details = el('details', 'how-recorded');
  details.appendChild(el('summary', '', 'How this was recorded'));
  if (r.events && r.events.length) {
    const list = el('ul');
    for (const ev of r.events) {
      const move = ev.from ? `${ev.from} → ${ev.to}` : ev.to;
      list.appendChild(el('li', '', `${formatIst(ev.at)} · ${ev.actor} · ${move}`));
    }
    details.appendChild(list);
  } else {
    details.appendChild(el('p', 'text-muted', 'No events recorded yet.'));
  }
  card.appendChild(details);
  receiptRoot.appendChild(card);
}

function renderSurvey(assignmentId) {
  if (surveyShownFor === assignmentId) return;
  const doneKey = `dd.survey.${assignmentId}`;
  if (sessionStorage.getItem(doneKey)) return;
  surveyShownFor = assignmentId;

  surveyRoot.innerHTML = '';
  const card = el('section', 'card');
  card.appendChild(el('h3', '', 'Two quick questions (optional)'));
  const form = el('form');
  form.noValidate = true;

  const q1 = el('fieldset', 'form-group');
  q1.appendChild(el('legend', '', 'Have you ever been marked late or blocked because an upload was slow?'));
  for (const [value, label] of [['YES', 'Yes'], ['NO', 'No'], ['UNSURE', 'Not sure']]) {
    const l = el('label', 'radio-label');
    const input = el('input');
    input.type = 'radio';
    input.name = 'markedLateBefore';
    input.value = value;
    l.appendChild(input);
    l.appendChild(document.createTextNode(` ${label}`));
    q1.appendChild(l);
  }
  form.appendChild(q1);

  const q2 = el('div', 'form-group');
  const sel = el('select');
  sel.id = 'survey-network';
  const lbl = el('label', '', 'Which network do you usually submit from?');
  lbl.htmlFor = 'survey-network';
  const blank = el('option', '', 'Choose…');
  blank.value = '';
  sel.appendChild(blank);
  for (const [value, label] of NETWORKS) {
    const o = el('option', '', label);
    o.value = value;
    sel.appendChild(o);
  }
  q2.appendChild(lbl);
  q2.appendChild(sel);
  form.appendChild(q2);

  const status = el('div', 'text-muted');
  status.setAttribute('role', 'status');
  const send = el('button', 'btn btn-primary', 'Send answers');
  send.type = 'submit';
  form.appendChild(send);
  form.appendChild(status);

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const picked = form.querySelector('input[name="markedLateBefore"]:checked');
    if (!picked && !sel.value) {
      status.textContent = 'Pick at least one answer, or just ignore this card.';
      return;
    }
    send.disabled = true;
    try {
      await apiFetch('/api/survey', {
        method: 'POST',
        body: {
          assignmentId,
          markedLateBefore: picked ? picked.value : null,
          usualNetwork: sel.value || null
        }
      });
      sessionStorage.setItem(doneKey, '1');
      surveyRoot.innerHTML = '';
      const thanks = el('section', 'card');
      thanks.setAttribute('role', 'status');
      thanks.appendChild(el('p', '', 'Thank you — your answers are stored anonymously.'));
      surveyRoot.appendChild(thanks);
    } catch (err) {
      send.disabled = false;
      status.textContent = err.detail || err.message || 'Could not send your answers. Try again.';
    }
  });

  card.appendChild(form);
  surveyRoot.appendChild(card);
}

function schedule(id) {
  clearTimeout(pollTimer);
  pollTimer = setTimeout(() => load(id, false), POLL_MS);
}

async function load(id, first) {
  if (first) renderState(stateContainer, { state: 'loading', title: 'Loading receipt…' });
  let receipt;
  try {
    receipt = await apiFetch(`/api/submissions/${encodeURIComponent(id)}`);
  } catch (err) {
    if (first) {
      renderState(stateContainer, {
        state: 'error',
        title: 'Could not load receipt',
        message: err.detail || err.message || 'Network error occurred.',
        retry: () => load(id, true)
      });
    } else {
      // Keep the last good receipt on screen and try again on the next tick.
      console.warn('Receipt refresh failed; will retry.', err);
      schedule(id);
    }
    return;
  }

  stateContainer.innerHTML = '';
  renderReceipt(receipt);
  renderSurvey(receipt.assignmentId);

  if (TERMINAL.has(receipt.status)) {
    return;
  }
  schedule(id);
}

const submissionId = new URLSearchParams(window.location.search).get('id');
if (!submissionId) {
  renderState(stateContainer, {
    state: 'error',
    title: 'No submission specified',
    message: 'Open a receipt from your assignments list.'
  });
} else {
  load(submissionId, true);
}
