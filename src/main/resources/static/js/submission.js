/**
 * DeadlineDrop — Teacher's read-only view of one submission.
 *
 * Shows the same receipt a student sees plus the event table (from, to, actor, time) so a
 * reviewer can see which actor won each state transition.
 */

import { apiFetch } from './api.js';
import { renderState, formatIst, chip, banner } from './ui.js';
import { renderTimeline } from './timeline.js';

const stateContainer = document.getElementById('state-container');
const root = document.getElementById('submission-root');

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function eventTable(events) {
  if (!events || events.length === 0) {
    return el('p', 'text-muted', 'No events recorded.');
  }
  const wrap = el('div', 'table-scroll');
  const table = el('table', 'table');
  const head = el('tr');
  for (const h of ['From', 'To', 'Actor', 'Time (IST)']) {
    const th = el('th', '', h);
    th.scope = 'col';
    head.appendChild(th);
  }
  const thead = el('thead');
  thead.appendChild(head);
  table.appendChild(thead);
  const body = el('tbody');
  for (const ev of events) {
    const tr = el('tr');
    tr.appendChild(el('td', '', ev.from || '—'));
    tr.appendChild(el('td', '', ev.to));
    tr.appendChild(el('td', '', ev.actor));
    tr.appendChild(el('td', '', formatIst(ev.at)));
    body.appendChild(tr);
  }
  table.appendChild(body);
  wrap.appendChild(table);
  return wrap;
}

function render(r, downloadStatus) {
  root.innerHTML = '';
  const card = el('section', 'card');
  card.appendChild(el('h1', '', r.assignmentTitle));

  const verdict = el('p', 'verdict-big');
  verdict.appendChild(r.timing === 'ON_TIME'
    ? chip('ontime', 'ON TIME')
    : r.timing === 'LATE' ? chip('late', 'LATE') : chip('pending', 'TIMING PENDING'));
  verdict.append(' ');
  verdict.appendChild(chip(r.status === 'VERIFIED' ? 'ontime' : r.status === 'COMMITTED' ? 'pending' : 'locked',
    r.status === 'COMMITTED' ? 'time locked, bytes arriving' : r.status.toLowerCase().replaceAll('_', ' ')));
  card.appendChild(verdict);

  if (r.status === 'EXPIRED') {
    card.appendChild(banner('error', 'The file never arrived before grace ended'));
  } else if (r.status === 'REJECTED_HASH_MISMATCH') {
    card.appendChild(banner('error', 'The file that arrived is not the file that was committed'));
  }

  card.appendChild(renderTimeline(r));

  card.appendChild(el('h2', '', 'SHA-256 (hash commitment)'));
  card.appendChild(el('code', 'mono', r.sha256));
  card.appendChild(el('p', 'text-muted',
    `${formatBytes(r.sizeBytes)} (${r.sizeBytes.toLocaleString()} bytes) · ${r.contentType}`));

  const actions = el('div', 'dash-actions');
  if (r.status === 'UPLOADED' || r.status === 'VERIFIED') {
    const btn = el('button', 'btn btn-primary', 'Download');
    btn.type = 'button';
    btn.addEventListener('click', async () => {
      btn.disabled = true;
      try {
        const res = await apiFetch(`/api/submissions/${encodeURIComponent(r.submissionId)}/download`);
        window.open(res.url, '_blank', 'noopener');
        downloadStatus.textContent = '';
      } catch (err) {
        downloadStatus.textContent = err.detail || err.message || 'Could not get the download link.';
      } finally {
        btn.disabled = false;
      }
    });
    actions.appendChild(btn);
  }
  actions.appendChild(downloadStatus);
  card.appendChild(actions);
  root.appendChild(card);

  const events = el('section', 'card');
  events.appendChild(el('h2', '', 'Who moved it, and when'));
  events.appendChild(eventTable(r.events));
  root.appendChild(events);

  const back = document.getElementById('back-link');
  back.href = `/teacher/dashboard.html?assignment=${encodeURIComponent(r.assignmentId)}`;
  back.textContent = 'Back to dashboard';
}

async function load(id) {
  renderState(stateContainer, { state: 'loading', title: 'Loading submission…' });
  try {
    const r = await apiFetch(`/api/submissions/${encodeURIComponent(id)}`);
    stateContainer.innerHTML = '';
    const status = el('span', 'text-muted');
    status.setAttribute('role', 'status');
    render(r, status);
  } catch (err) {
    renderState(stateContainer, {
      state: 'error',
      title: 'Could not load submission',
      message: err.detail || err.message || 'Network error occurred.',
      retry: () => load(id)
    });
  }
}

const submissionId = new URLSearchParams(window.location.search).get('id');
if (!submissionId) {
  renderState(stateContainer, {
    state: 'error',
    title: 'No submission specified',
    message: 'Open a submission from the dashboard.'
  });
} else {
  load(submissionId);
}
