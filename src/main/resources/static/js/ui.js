/**
 * DeadlineDrop UI Component Helpers
 *
 * Plain ES module for rendering accessible state containers, banners, chips,
 * IST date-time formatting, and countdown timers.
 */

import { serverNow } from './api.js';

/**
 * Render an accessible state container into a target element.
 *
 * @param {HTMLElement} el - Target DOM element
 * @param {object} options - { state: 'empty'|'loading'|'error'|'partial'|'success', title, message, retry }
 */
export function renderState(el, { state = 'empty', title, message, retry } = {}) {
  if (!el) return;
  el.innerHTML = '';

  const container = document.createElement('div');
  container.className = `card state-container state-${state}`;

  if (state === 'loading' || state === 'partial') {
    container.setAttribute('aria-live', 'polite');
    container.setAttribute('role', 'status');
  } else if (state === 'error') {
    container.setAttribute('role', 'alert');
  } else {
    container.setAttribute('role', 'status');
  }

  if (state === 'loading' || state === 'partial') {
    const progress = document.createElement('div');
    progress.className = 'progress indeterminate';
    progress.setAttribute('aria-hidden', 'true');
    const progressBar = document.createElement('div');
    progressBar.className = 'progress-bar';
    progress.appendChild(progressBar);
    container.appendChild(progress);
  }

  if (title) {
    const heading = document.createElement('h3');
    heading.textContent = title;
    container.appendChild(heading);
  }

  if (message) {
    const p = document.createElement('p');
    p.textContent = message;
    container.appendChild(p);
  }

  if (typeof retry === 'function') {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn';
    btn.textContent = 'Retry';
    btn.addEventListener('click', () => retry());
    container.appendChild(btn);
  }

  el.appendChild(container);
}

/**
 * Construct an accessible banner element.
 *
 * @param {'info'|'warn'|'error'} kind - Variant type
 * @param {string} text - Message content
 * @returns {HTMLDivElement}
 */
export function banner(kind, text) {
  const el = document.createElement('div');
  el.className = `banner banner-${kind}`;
  el.setAttribute('role', kind === 'error' ? 'alert' : 'status');
  el.textContent = text;
  return el;
}

/**
 * Construct a semantic status chip (always carries words, never colour alone).
 *
 * @param {'ontime'|'late'|'locked'|'pending'|'muted'} kind - Chip type
 * @param {string} text - Chip label text
 * @returns {HTMLSpanElement}
 */
export function chip(kind, text) {
  const el = document.createElement('span');
  el.className = `chip chip-${kind}`;
  el.textContent = text;
  return el;
}

const istFormatter = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata',
  year: 'numeric',
  month: 'short',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false
});

/**
 * Format a timestamp into Indian Standard Time (Asia/Kolkata).
 *
 * @param {Date|number|string} instant - Date representation
 * @returns {string} Formatted date-time string
 */
export function formatIst(instant) {
  if (!instant) return '';
  const date = instant instanceof Date ? instant : new Date(instant);
  if (isNaN(date.getTime())) return '';
  return istFormatter.format(date);
}

/**
 * Start a countdown timer driven by serverNow().
 *
 * @param {HTMLElement} el - Element to display countdown text
 * @param {number} deadlineEpochMs - Target deadline in epoch milliseconds
 * @returns {Function} Stop function to cancel the timer
 */
export function countdown(el, deadlineEpochMs) {
  if (!el || typeof deadlineEpochMs !== 'number') {
    return () => {};
  }

  function update() {
    const remainingMs = deadlineEpochMs - serverNow();
    if (remainingMs <= 0) {
      el.textContent = 'Deadline passed';
      return;
    }

    const totalSeconds = Math.floor(remainingMs / 1000);
    const days = Math.floor(totalSeconds / 86400);
    const hours = Math.floor((totalSeconds % 86400) / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;

    const parts = [];
    if (days > 0) parts.push(`${days}d`);
    if (hours > 0 || days > 0) parts.push(`${hours}h`);
    parts.push(`${minutes}m`);
    parts.push(`${seconds.toString().padStart(2, '0')}s`);

    el.textContent = parts.join(' ');
  }

  update();
  const timerId = setInterval(update, 1000);
  return () => clearInterval(timerId);
}
