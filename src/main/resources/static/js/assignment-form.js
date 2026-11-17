import { apiFetch, ApiError } from './api.js';
import { banner, formatIst } from './ui.js';

const params = new URLSearchParams(window.location.search);
const assignmentId = params.get('id');
const courseId = params.get('course');
let version = null;

function clearErrors() {
  ['title', 'deadlineAt', 'graceSeconds', 'maxBytes', 'allowedTypes'].forEach(field => {
    document.getElementById(`${field}-error`).textContent = '';
  });
  document.getElementById('assignment-banner').replaceChildren();
}

function showErrors(error) {
  for (const item of error.errors || []) {
    const target = document.getElementById(`${item.field}-error`);
    if (target) target.textContent = item.message;
  }
}

function instantFromInputs() {
  const date = document.getElementById('deadline-date').value;
  const time = document.getElementById('deadline-time').value;
  if (!date || !time) return null;
  const instant = new Date(`${date}T${time}:00+05:30`);
  return Number.isNaN(instant.getTime()) ? null : instant;
}

function updateStudentDeadline() {
  const instant = instantFromInputs();
  document.getElementById('students-see').textContent = instant
    ? `Students see: ${formatIst(instant)}`
    : 'Students see: choose a deadline';
}

function setDeadlineInputs(instant) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23'
  }).formatToParts(new Date(instant)).reduce((values, part) => ({ ...values, [part.type]: part.value }), {});
  document.getElementById('deadline-date').value = `${parts.year}-${parts.month}-${parts.day}`;
  document.getElementById('deadline-time').value = `${parts.hour}:${parts.minute}`;
  updateStudentDeadline();
}

function payload() {
  const deadline = instantFromInputs();
  const allowedTypes = [...document.querySelectorAll('input[name="allowedTypes"]:checked')].map(input => input.value);
  return {
    title: document.getElementById('assignment-title').value.trim(),
    deadlineAt: deadline ? deadline.toISOString() : null,
    graceSeconds: Number(document.getElementById('grace-minutes').value) * 60,
    maxBytes: Number(document.getElementById('max-size-mb').value) * 1024 * 1024,
    allowedTypes
  };
}

async function loadForEdit() {
  if (!assignmentId) return;
  document.getElementById('assignment-form-heading').textContent = 'Edit assignment';
  document.getElementById('assignment-submit').textContent = 'Save changes';
  try {
    const assignment = await apiFetch(`/api/assignments/${encodeURIComponent(assignmentId)}`);
    version = assignment.version;
    document.getElementById('assignment-title').value = assignment.title;
    setDeadlineInputs(assignment.deadlineAt);
    document.getElementById('grace-minutes').value = assignment.graceSeconds / 60;
    document.getElementById('max-size-mb').value = assignment.maxBytes / (1024 * 1024);
    document.querySelectorAll('input[name="allowedTypes"]').forEach(input => {
      input.checked = assignment.allowedTypes.includes(input.value);
    });
  } catch (error) {
    document.getElementById('assignment-banner').appendChild(banner('error', error.detail || 'Could not load this assignment.'));
    document.getElementById('assignment-submit').disabled = true;
  }
}

async function submit(event) {
  event.preventDefault();
  clearErrors();
  const request = payload();
  try {
    if (assignmentId) {
      request.version = version;
      await apiFetch(`/api/assignments/${encodeURIComponent(assignmentId)}`, { method: 'PATCH', body: request });
    } else {
      if (!courseId) throw new Error('Choose a course before creating an assignment.');
      await apiFetch(`/api/courses/${encodeURIComponent(courseId)}/assignments`, { method: 'POST', body: request });
    }
    window.location.href = '/teacher/courses.html';
  } catch (error) {
    const target = document.getElementById('assignment-banner');
    if (error instanceof ApiError && error.status === 409 && error.code === 'VERSION_CONFLICT') {
      const reload = document.createElement('button');
      reload.type = 'button';
      reload.className = 'btn';
      reload.textContent = 'Reload';
      reload.addEventListener('click', () => window.location.reload());
      const message = banner('error', 'Someone else edited this assignment, reload');
      message.append(' ');
      message.appendChild(reload);
      target.appendChild(message);
    } else if (error instanceof ApiError && error.status === 409 && error.code === 'WRONG_STATE') {
      target.appendChild(banner('error', 'The deadline has passed; this assignment can no longer be edited'));
    } else {
      showErrors(error);
      if (!(error.errors || []).length) target.appendChild(banner('error', error.detail || error.message || 'Could not save assignment.'));
    }
  }
}

function init() {
  document.getElementById('deadline-date').addEventListener('input', updateStudentDeadline);
  document.getElementById('deadline-time').addEventListener('input', updateStudentDeadline);
  document.getElementById('assignment-form').addEventListener('submit', submit);
  loadForEdit();
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
else init();
