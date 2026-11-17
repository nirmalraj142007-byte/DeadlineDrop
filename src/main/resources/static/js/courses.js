import { apiFetch, ApiError } from './api.js';
import { banner, formatIst, renderState } from './ui.js';

let cachedCourses = null;
// Enrolment results survive the table re-render that follows a successful enrolment.
const enrolFeedback = new Map();

function clearCourseErrors() {
  document.getElementById('course-code-error').textContent = '';
  document.getElementById('course-title-error').textContent = '';
  document.getElementById('course-form-banner').replaceChildren();
}

function showCourseErrors(error) {
  const fields = { code: 'course-code-error', title: 'course-title-error' };
  for (const item of error.errors || []) {
    const target = document.getElementById(fields[item.field]);
    if (target) target.textContent = item.message;
  }
}

function enrollmentSummary(result) {
  const unknown = result.unknownEmails || [];
  const unknownText = unknown.length ? `, ${unknown.length} unknown: ${unknown.join(', ')}` : '';
  return `${result.enrolled} enrolled, ${result.alreadyEnrolled} already enrolled${unknownText}`;
}

function assignmentList(course, assignments) {
  const wrapper = document.createElement('div');
  const actions = document.createElement('div');
  actions.className = 'course-actions';
  const newAssignment = document.createElement('a');
  newAssignment.className = 'btn btn-primary';
  newAssignment.href = `/teacher/assignment-form.html?course=${encodeURIComponent(course.id)}`;
  newAssignment.textContent = 'New assignment';
  actions.appendChild(newAssignment);
  wrapper.appendChild(actions);

  if (assignments.length === 0) {
    const none = document.createElement('p');
    none.className = 'text-muted';
    none.textContent = 'No assignments yet.';
    wrapper.appendChild(none);
    return wrapper;
  }

  const list = document.createElement('ul');
  for (const assignment of assignments) {
    const item = document.createElement('li');
    const title = document.createElement('strong');
    title.textContent = assignment.title;
    item.appendChild(title);
    item.append(` — due ${formatIst(assignment.deadlineAt)} `);
    const dashboard = document.createElement('a');
    dashboard.href = `/teacher/dashboard.html?assignment=${encodeURIComponent(assignment.id)}`;
    dashboard.textContent = 'Dashboard';
    item.appendChild(dashboard);
    item.append(' · ');
    const edit = document.createElement('a');
    edit.href = `/teacher/assignment-form.html?id=${encodeURIComponent(assignment.id)}`;
    edit.textContent = 'Edit';
    item.appendChild(edit);
    list.appendChild(item);
  }
  wrapper.appendChild(list);
  return wrapper;
}

function renderCourses(items) {
  const container = document.getElementById('courses-container');
  container.replaceChildren();
  const table = document.createElement('table');
  table.className = 'table';
  const head = document.createElement('thead');
  const headRow = document.createElement('tr');
  ['Code', 'Title', 'Students', 'Next deadline', 'Assignments', 'Enrol students'].forEach(label => {
    const cell = document.createElement('th');
    cell.textContent = label;
    headRow.appendChild(cell);
  });
  head.appendChild(headRow);
  table.appendChild(head);
  const body = document.createElement('tbody');
  for (const item of items) {
    const row = document.createElement('tr');
    for (const value of [item.course.code, item.course.title, String(item.course.students)]) {
      const cell = document.createElement('td');
      cell.textContent = value;
      row.appendChild(cell);
    }
    const deadline = document.createElement('td');
    deadline.textContent = item.assignments.length ? formatIst(item.assignments[0].deadlineAt) : 'No assignments';
    row.appendChild(deadline);
    const assignments = document.createElement('td');
    assignments.appendChild(assignmentList(item.course, item.assignments));
    row.appendChild(assignments);
    const enrollment = document.createElement('td');
    const form = document.createElement('form');
    const label = document.createElement('label');
    const textareaId = `enrol-${item.course.id}`;
    label.htmlFor = textareaId;
    label.textContent = 'One email per line';
    const textarea = document.createElement('textarea');
    textarea.id = textareaId;
    textarea.rows = 4;
    textarea.required = true;
    const submit = document.createElement('button');
    submit.className = 'btn';
    submit.type = 'submit';
    submit.textContent = 'Enrol students';
    const feedback = document.createElement('p');
    feedback.className = 'text-muted';
    feedback.setAttribute('aria-live', 'polite');
    feedback.textContent = enrolFeedback.get(item.course.id) || '';
    form.append(label, textarea, submit, feedback);
    form.addEventListener('submit', async event => {
      event.preventDefault();
      const emails = textarea.value.split(/\r?\n/).map(email => email.trim()).filter(Boolean);
      if (emails.length === 0) {
        feedback.textContent = 'Enter at least one email address.';
        return;
      }
      submit.disabled = true;
      feedback.textContent = '';
      try {
        const result = await apiFetch(`/api/courses/${encodeURIComponent(item.course.id)}/enrollments`, {
          method: 'POST', body: { emails }
        });
        enrolFeedback.set(item.course.id, enrollmentSummary(result));
        feedback.textContent = enrolFeedback.get(item.course.id);
        textarea.value = '';
        await loadCourses();
      } catch (error) {
        feedback.textContent = error.detail || 'Could not enrol students.';
      } finally {
        submit.disabled = false;
      }
    });
    enrollment.appendChild(form);
    row.appendChild(enrollment);
    body.appendChild(row);
  }
  table.appendChild(body);
  container.appendChild(table);
}

export async function loadCourses() {
  const state = document.getElementById('courses-state');
  if (!cachedCourses) renderState(state, { state: 'loading', title: 'Loading courses…' });
  try {
    const courses = await apiFetch('/api/courses');
    if (!courses.length) {
      cachedCourses = [];
      document.getElementById('courses-container').replaceChildren();
      renderState(state, { state: 'empty', message: 'No courses yet' });
      return;
    }
    const settled = await Promise.allSettled(courses.map(course =>
      apiFetch(`/api/courses/${encodeURIComponent(course.id)}/assignments`)));
    const items = courses.map((course, index) => ({
      course,
      assignments: settled[index].status === 'fulfilled' ? settled[index].value : []
    }));
    cachedCourses = items;
    renderCourses(items);
    const failures = settled.filter(result => result.status === 'rejected').length;
    if (failures) {
      renderState(state, { state: 'partial', title: 'Some assignment details could not load', message: 'Retry to refresh the course list.', retry: loadCourses });
    } else {
      state.replaceChildren();
    }
  } catch (error) {
    if (cachedCourses && cachedCourses.length) {
      renderState(state, { state: 'partial', title: 'Could not refresh courses', message: error.detail || 'Retry to refresh the course list.', retry: loadCourses });
    } else {
      document.getElementById('courses-container').replaceChildren();
      renderState(state, { state: 'error', title: 'Could not load courses', message: error.detail || 'Retry to load courses.', retry: loadCourses });
    }
  }
}

function initCourseForm() {
  document.getElementById('course-form').addEventListener('submit', async event => {
    event.preventDefault();
    // currentTarget is null once the handler awaits, so keep a reference to the form now.
    const form = event.currentTarget;
    clearCourseErrors();
    const code = document.getElementById('course-code').value.trim();
    const title = document.getElementById('course-title').value.trim();
    try {
      await apiFetch('/api/courses', { method: 'POST', body: { code, title } });
      form.reset();
      await loadCourses();
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        document.getElementById('course-form-banner').appendChild(banner('error', 'That course code is taken'));
      } else {
        showCourseErrors(error);
        if (!(error.errors || []).length) document.getElementById('course-form-banner').appendChild(banner('error', error.detail || 'Could not create course.'));
      }
    }
  });
}

function init() {
  initCourseForm();
  loadCourses();
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
else init();
