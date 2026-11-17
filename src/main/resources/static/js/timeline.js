/**
 * DeadlineDrop — Shared submission timeline markup.
 *
 * Used by the student receipt and the teacher's read-only submission page so both show the
 * same three steps: time locked (server clock), file arrived, verified.
 */

import { formatIst } from './ui.js';

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

/**
 * Build the three-step timeline for a receipt-shaped object.
 *
 * @param {{committedAt: string, uploadedAt: ?string, verifiedAt: ?string}} r
 * @returns {HTMLOListElement}
 */
export function renderTimeline(r) {
  const steps = el('ol', 'timeline');
  steps.appendChild(timelineStep('Time locked (server clock)', r.committedAt, 'Waiting'));
  steps.appendChild(timelineStep('File arrived', r.uploadedAt, 'Waiting for your file'));
  steps.appendChild(timelineStep('Verified', r.verifiedAt, 'Waiting for verification'));
  return steps;
}
