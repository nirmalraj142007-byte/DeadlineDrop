/**
 * DeadlineDrop Student Assignments Screen Controller
 */

import { apiFetch } from './api.js';
import { renderState, chip, formatIst, countdown } from './ui.js';

let activeTimers = [];
let cachedAssignments = null;

function clearTimers() {
  for (const stop of activeTimers) {
    if (typeof stop === 'function') {
      stop();
    }
  }
  activeTimers = [];
}

/**
 * Render assignment items into the table container.
 *
 * @param {HTMLElement} container
 * @param {Array} assignments
 */
function renderAssignments(container, assignments) {
  clearTimers();
  container.innerHTML = '';

  const table = document.createElement('table');
  table.className = 'table';

  const thead = document.createElement('thead');
  const headerRow = document.createElement('tr');
  ['Course', 'Assignment', 'Deadline (IST)', 'Remaining', 'Status', 'Action'].forEach(text => {
    const th = document.createElement('th');
    th.textContent = text;
    headerRow.appendChild(th);
  });
  thead.appendChild(headerRow);
  table.appendChild(thead);

  const tbody = document.createElement('tbody');
  for (const item of assignments) {
    const row = document.createElement('tr');

    // Course code
    const tdCourse = document.createElement('td');
    tdCourse.textContent = item.courseCode || '—';
    row.appendChild(tdCourse);

    // Title linking to submit.html?assignment=<id>
    const tdTitle = document.createElement('td');
    const titleLink = document.createElement('a');
    titleLink.href = `/student/submit.html?assignment=${encodeURIComponent(item.assignment.id)}`;
    titleLink.textContent = item.assignment.title;
    tdTitle.appendChild(titleLink);
    row.appendChild(tdTitle);

    // Deadline in IST
    const tdDeadline = document.createElement('td');
    tdDeadline.textContent = formatIst(item.assignment.deadlineAt);
    row.appendChild(tdDeadline);

    // Live countdown
    const tdRemaining = document.createElement('td');
    const remainingSpan = document.createElement('span');
    const deadlineMs = new Date(item.assignment.deadlineAt).getTime();
    const stopTimer = countdown(remainingSpan, deadlineMs);
    activeTimers.push(stopTimer);
    tdRemaining.appendChild(remainingSpan);
    row.appendChild(tdRemaining);

    // Status chip
    const tdStatus = document.createElement('td');
    let statusChip;
    if (!item.mySubmission) {
      statusChip = chip('muted', 'Not submitted');
    } else if (item.mySubmission.status === 'COMMITTED') {
      statusChip = chip('locked', 'Time locked');
    } else if (item.mySubmission.timing === 'ON_TIME') {
      statusChip = chip('ontime', 'On time ✓');
    } else {
      statusChip = chip('late', 'Late');
    }
    tdStatus.appendChild(statusChip);
    row.appendChild(tdStatus);

    // Action button
    const tdAction = document.createElement('td');
    const actionLink = document.createElement('a');
    actionLink.href = `/student/submit.html?assignment=${encodeURIComponent(item.assignment.id)}`;
    actionLink.className = 'btn';
    actionLink.textContent = item.mySubmission ? 'View / Resubmit' : 'Submit';
    tdAction.appendChild(actionLink);
    row.appendChild(tdAction);

    tbody.appendChild(row);
  }

  table.appendChild(tbody);
  container.appendChild(table);
}

/**
 * Fetch and display the student's assignments.
 */
export async function loadAssignments() {
  const stateContainer = document.getElementById('state-container');
  const assignmentsContainer = document.getElementById('assignments-container');

  if (!cachedAssignments) {
    renderState(stateContainer, { state: 'loading', title: 'Loading assignments…' });
    if (assignmentsContainer) assignmentsContainer.innerHTML = '';
  }

  try {
    const data = await apiFetch('/api/me/assignments');
    cachedAssignments = data;

    if (!data || data.length === 0) {
      renderState(stateContainer, {
        state: 'empty',
        message: 'No assignments yet'
      });
      if (assignmentsContainer) assignmentsContainer.innerHTML = '';
      return;
    }

    // Success state: clear state container and display assignments
    stateContainer.innerHTML = '';
    renderAssignments(assignmentsContainer, data);
  } catch (err) {
    if (cachedAssignments && cachedAssignments.length > 0) {
      // Partial state: retain what loaded plus render inline error with Retry
      renderState(stateContainer, {
        state: 'partial',
        title: 'Could not refresh assignments',
        message: err.detail || err.message || 'Network error occurred while updating assignments.',
        retry: () => loadAssignments()
      });
    } else {
      // Full error state with Retry
      renderState(stateContainer, {
        state: 'error',
        title: 'Could not load assignments',
        message: err.detail || err.message || 'Failed to load assignments.',
        retry: () => loadAssignments()
      });
      if (assignmentsContainer) assignmentsContainer.innerHTML = '';
    }
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', loadAssignments);
} else {
  loadAssignments();
}
