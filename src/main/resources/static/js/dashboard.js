/**
 * DeadlineDrop — Teacher live dashboard.
 *
 * Polls GET /api/assignments/{id}/dashboard every 5 s with If-None-Match. A 304 keeps the view.
 * A failed poll keeps the last data on screen and shows an amber "Last updated n s ago" badge;
 * the screen is never blanked after the first successful load.
 */

import { apiFetch, serverNow } from './api.js';
import { renderState, formatIst, chip } from './ui.js';

const POLL_MS = 5000;

const TILES = [
  { key: 'COMMITTED', label: 'Time locked, bytes arriving', kind: 'amber' },
  { key: 'UPLOADED', label: 'Bytes arrived', kind: '' },
  { key: 'VERIFIED', label: 'Verified', kind: 'green' },
  { key: 'LATE', label: 'Late', kind: 'red', timing: true },
  { key: 'EXPIRED', label: 'Expired', kind: 'red' },
  { key: 'REJECTED_HASH_MISMATCH', label: 'Rejected', kind: 'red' },
  { key: 'NOT_SUBMITTED', label: 'Not submitted', kind: '' }
];

const STATUS_CHIPS = {
  COMMITTED: ['pending', 'time locked, bytes arriving'],
  UPLOADED: ['locked', 'bytes arrived'],
  VERIFIED: ['ontime', 'verified'],
  EXPIRED: ['late', 'expired'],
  REJECTED_HASH_MISMATCH: ['late', 'rejected'],
  NOT_SUBMITTED: ['muted', 'not submitted']
};

const stateContainer = document.getElementById('state-container');
const root = document.getElementById('dashboard-root');
const titleEl = document.getElementById('dash-title');
const deadlineEl = document.getElementById('dash-deadline');
const graceEl = document.getElementById('dash-grace');
const countdownEl = document.getElementById('dash-countdown');
const failedBadge = document.getElementById('failed-badge');
const staleBadge = document.getElementById('stale-badge');
const tilesEl = document.getElementById('tiles');
const rowsEl = document.getElementById('rows');
const downloadAllBtn = document.getElementById('download-all');
const downloadStatus = document.getElementById('download-status');

let assignmentId = null;
let etag = null;
let data = null;
let lastOkAtMs = 0;
let failing = false;
let pollTimer = null;

function td(content) {
  const cell = document.createElement('td');
  if (content instanceof Node) cell.appendChild(content);
  else cell.textContent = content;
  return cell;
}

function renderTiles(d) {
  tilesEl.innerHTML = '';
  for (const t of TILES) {
    const count = (t.timing ? d.countsByTiming : d.countsByStatus)[t.key] || 0;
    const tile = document.createElement('div');
    tile.className = t.kind && count > 0 ? `tile tile-${t.kind}` : 'tile';
    const n = document.createElement('span');
    n.className = 'tile-count';
    n.textContent = String(count);
    const label = document.createElement('span');
    label.className = 'tile-label';
    label.textContent = t.label;
    tile.append(n, label);
    tilesEl.appendChild(tile);
  }
}

async function openDownload(button, submissionId) {
  button.disabled = true;
  try {
    const res = await apiFetch(`/api/submissions/${encodeURIComponent(submissionId)}/download`);
    window.open(res.url, '_blank', 'noopener');
  } catch (err) {
    downloadStatus.textContent = err.detail || err.message || 'Could not get the download link.';
  } finally {
    button.disabled = false;
  }
}

function renderRows(d) {
  rowsEl.innerHTML = '';
  if (d.rows.length === 0) {
    const tr = document.createElement('tr');
    const cell = td('No students are enrolled in this course yet.');
    cell.colSpan = 6;
    tr.appendChild(cell);
    rowsEl.appendChild(tr);
    return;
  }
  for (const row of d.rows) {
    const tr = document.createElement('tr');
    if (row.status === 'COMMITTED') tr.className = 'row-amber';

    const who = document.createElement('div');
    if (row.submissionId) {
      const link = document.createElement('a');
      link.href = `/teacher/submission.html?id=${encodeURIComponent(row.submissionId)}`;
      link.textContent = row.displayName || row.email;
      who.appendChild(link);
    } else {
      who.textContent = row.displayName || row.email;
    }
    const email = document.createElement('div');
    email.className = 'text-muted';
    email.textContent = row.email;
    who.appendChild(email);
    tr.appendChild(td(who));

    const [kind, words] = STATUS_CHIPS[row.status] || ['muted', row.status.toLowerCase()];
    const status = document.createElement('div');
    status.appendChild(chip(kind, words));
    if (row.timing === 'LATE') {
      status.append(' ');
      status.appendChild(chip('late', 'late'));
    }
    tr.appendChild(td(status));

    tr.appendChild(td(row.committedAt ? formatIst(row.committedAt) : '—'));
    tr.appendChild(td(row.uploadedAt ? formatIst(row.uploadedAt) : '—'));
    tr.appendChild(td(row.verifiedAt ? formatIst(row.verifiedAt) : '—'));

    const action = td('');
    action.className = 'actions-cell';
    if (row.submissionId && (row.status === 'UPLOADED' || row.status === 'VERIFIED')) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'btn';
      btn.textContent = 'Download';
      btn.setAttribute('aria-label', `Download file from ${row.displayName || row.email}`);
      btn.addEventListener('click', () => openDownload(btn, row.submissionId));
      action.appendChild(btn);
    } else {
      action.textContent = '—';
    }
    tr.appendChild(action);
    rowsEl.appendChild(tr);
  }
}

function render(d) {
  document.title = `DeadlineDrop — ${d.title}`;
  titleEl.textContent = d.title;
  deadlineEl.textContent = `Deadline: ${formatIst(d.deadlineAt)} IST`;
  graceEl.textContent = `Grace ends: ${formatIst(d.graceEndsAt)} IST`;
  renderTiles(d);
  renderRows(d);
  if (d.failedJobs > 0) {
    failedBadge.textContent = `Failed checks: ${d.failedJobs}`;
    failedBadge.hidden = false;
  } else {
    failedBadge.hidden = true;
  }
  tick();
}

function formatRemaining(ms) {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return `${h > 0 ? `${h}h ` : ''}${m}m ${String(s).padStart(2, '0')}s`;
}

/** Once per second: countdown, stale badge and the Download all gate. */
function tick() {
  if (!data) return;
  const now = serverNow();
  const deadline = Date.parse(data.deadlineAt);
  const graceEnd = Date.parse(data.graceEndsAt);
  if (now < deadline) {
    countdownEl.textContent = `Deadline in ${formatRemaining(deadline - now)}`;
  } else if (now < graceEnd) {
    countdownEl.textContent = `Deadline passed. Grace ends in ${formatRemaining(graceEnd - now)}`;
  } else {
    countdownEl.textContent = 'Grace is over. Results are final.';
  }

  const open = now >= graceEnd;
  downloadAllBtn.disabled = !open || downloading;
  downloadAllBtn.title = open ? '' : `Available after grace ends at ${formatIst(data.graceEndsAt)}`;

  if (failing) {
    const secs = Math.max(0, Math.round((Date.now() - lastOkAtMs) / 1000));
    staleBadge.textContent = `Last updated ${secs} s ago`;
    staleBadge.hidden = false;
  } else {
    staleBadge.hidden = true;
  }
}

let downloading = false;

async function downloadAll() {
  downloading = true;
  tick();
  downloadStatus.textContent = 'Preparing the zip…';
  try {
    const blob = await apiFetch(`/api/assignments/${encodeURIComponent(assignmentId)}/download-all`,
      { responseType: 'blob' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `assignment-${assignmentId}-submissions.zip`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
    downloadStatus.textContent = 'Zip saved.';
  } catch (err) {
    downloadStatus.textContent = err.detail || err.message || 'Download failed. Try again.';
  } finally {
    downloading = false;
    tick();
  }
}

function schedule() {
  clearTimeout(pollTimer);
  pollTimer = setTimeout(() => poll(false), POLL_MS);
}

async function poll(first) {
  if (first) renderState(stateContainer, { state: 'loading', title: 'Loading dashboard…' });
  const meta = {};
  const headers = etag ? { 'If-None-Match': etag } : {};
  try {
    const body = await apiFetch(`/api/assignments/${encodeURIComponent(assignmentId)}/dashboard`,
      { headers, meta });
    failing = false;
    lastOkAtMs = Date.now();
    if (meta.status !== 304 && body) {
      data = body;
      etag = meta.etag;
      stateContainer.innerHTML = '';
      root.hidden = false;
      render(data);
    } else {
      tick();
    }
  } catch (err) {
    if (!data) {
      renderState(stateContainer, {
        state: 'error',
        title: 'Could not load the dashboard',
        message: err.detail || err.message || 'Network error occurred.',
        retry: () => poll(true)
      });
      return;
    }
    // Keep the last good data on screen; the badge says how old it is.
    console.warn('Dashboard refresh failed; keeping the last data.', err);
    failing = true;
    tick();
  }
  schedule();
}

assignmentId = new URLSearchParams(window.location.search).get('assignment');
if (!assignmentId) {
  renderState(stateContainer, {
    state: 'error',
    title: 'No assignment specified',
    message: 'Open a dashboard from the courses page.'
  });
} else {
  downloadAllBtn.addEventListener('click', downloadAll);
  setInterval(tick, 1000);
  poll(true);
}
