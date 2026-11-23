import { apiFetch, ApiError } from './api.js';
import { banner, renderState } from './ui.js';

const stateEl = document.getElementById('console-state');
const armEl = document.getElementById('arm-value');
const serverTimeEl = document.getElementById('server-time');
const skewEl = document.getElementById('clock-skew');
const chaosSection = document.getElementById('chaos-section');
const chaosStatus = document.getElementById('chaos-status');
const haltButton = document.getElementById('halt-button');
const timeFormatter = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata', dateStyle: 'medium', timeStyle: 'medium'
});

function showError(error) {
  const detail = error instanceof ApiError ? error.detail : error.message;
  renderState(stateEl, {
    state: 'error',
    title: 'Could not load the admin console',
    message: detail || 'A network error prevented the console from loading.'
  });
}

async function loadConsole() {
  if (sessionStorage.getItem('role') !== 'ADMIN') {
    renderState(stateEl, {
      state: 'error',
      title: 'Administrator access required',
      message: 'Sign in with an administrator account to view this console.'
    });
    return;
  }

  renderState(stateEl, { state: 'loading', title: 'Loading deployment details…' });
  try {
    const [config, time] = await Promise.all([
      apiFetch('/api/config'),
      (async () => {
        const before = Date.now();
        const response = await apiFetch('/api/time');
        const after = Date.now();
        return { ...response, localMidpoint: (before + after) / 2 };
      })()
    ]);

    armEl.textContent = `${config.arm} — ${config.armLabel}`;
    const skewMs = Math.round(time.epochMs - time.localMidpoint);
    skewEl.textContent = `${skewMs > 0 ? '+' : ''}${skewMs} ms (server time minus local clock)`;
    const estimatedOffset = time.epochMs - time.localMidpoint;
    serverTimeEl.textContent = `${timeFormatter.format(new Date(Date.now() + estimatedOffset))} IST`;
    stateEl.replaceChildren();
    await loadChaosStatus();
  } catch (error) {
    showError(error);
  }
}

async function loadChaosStatus() {
  chaosSection.hidden = false;
  try {
    const status = await apiFetch('/admin/chaos/status');
    if (status.enabled) {
      chaosStatus.textContent = 'Chaos is enabled for this instance.';
      haltButton.hidden = false;
    } else {
      chaosStatus.textContent = 'Chaos disabled (CHAOS_ENABLED is off).';
    }
  } catch (error) {
    haltButton.hidden = true;
    if (error instanceof ApiError && error.status === 404) {
      chaosStatus.textContent = 'Chaos disabled (CHAOS_ENABLED is off).';
      return;
    }
    chaosStatus.replaceChildren();
    chaosStatus.appendChild(banner('error', error.detail || error.message || 'Could not check chaos status.'));
  }
}

haltButton.addEventListener('click', async () => {
  if (!window.confirm('Halt this instance now? It will stop processing requests.')) return;

  haltButton.disabled = true;
  chaosStatus.textContent = 'Requesting instance halt…';
  try {
    const result = await apiFetch('/admin/chaos/halt', { method: 'POST' });
    chaosStatus.textContent = `Halt requested for instance ${result.halting}.`;
    haltButton.hidden = true;
  } catch (error) {
    chaosStatus.replaceChildren();
    chaosStatus.appendChild(banner('error', error.detail || error.message || 'Could not halt this instance.'));
    haltButton.disabled = false;
  }
});

loadConsole();
