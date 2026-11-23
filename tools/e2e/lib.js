// Shared helpers for the end-to-end scripts. They drive the real stack over HTTP, the way a
// student's browser and a teacher's dashboard do. Nothing here is mocked.
import http from 'k6/http';
import { check, fail } from 'k6';

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
export const PASSWORD = __ENV.PASSWORD || '';
export const TEACHER_EMAIL = 'teacher@dd.local';
export const CONTENT_TYPE = 'application/pdf';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function requirePassword() {
  if (!PASSWORD) {
    fail('PASSWORD (the seed password) must be passed with -e PASSWORD=...');
  }
}

export function login(email) {
  const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email: email, password: PASSWORD }),
    { headers: JSON_HEADERS });
  if (res.status !== 200) {
    fail(`login failed for ${email}: ${res.status} ${res.body}`);
  }
  return res.json('accessToken');
}

export function bearer(token, extra) {
  return { headers: Object.assign({ Authorization: `Bearer ${token}` }, extra || {}) };
}

// A fresh assignment per run, so reruns never meet an earlier run's live submissions.
export function createAssignment(teacherToken, title, deadlineMs, graceSeconds) {
  const courses = http.get(`${BASE_URL}/api/courses`, bearer(teacherToken));
  if (courses.status !== 200) {
    fail(`listing courses failed: ${courses.status} ${courses.body}`);
  }
  const demo = courses.json().filter((c) => c.code === 'DEMO101')[0];
  if (!demo) {
    fail('course DEMO101 not found; run the seed first');
  }
  const res = http.post(`${BASE_URL}/api/courses/${demo.id}/assignments`, JSON.stringify({
    title: title,
    deadlineAt: new Date(deadlineMs).toISOString(),
    graceSeconds: graceSeconds,
    maxBytes: 26214400,
    allowedTypes: [CONTENT_TYPE],
  }), bearer(teacherToken, JSON_HEADERS));
  if (res.status !== 201 && res.status !== 200) {
    fail(`creating the assignment failed: ${res.status} ${res.body}`);
  }
  return res.json('id');
}

// Random bytes, so no two runs (or students) upload the same file.
export function randomFile(sizeBytes) {
  const bytes = new Uint8Array(sizeBytes);
  for (let i = 0; i < sizeBytes; i++) {
    bytes[i] = (Math.random() * 256) | 0;
  }
  return bytes.buffer;
}

export function sleepUntil(epochMs, sleepFn) {
  const wait = epochMs - Date.now();
  if (wait > 0) {
    sleepFn(wait / 1000);
  }
}

export function exportRows(teacherToken, assignmentId) {
  const res = http.get(`${BASE_URL}/api/assignments/${assignmentId}/export`, bearer(teacherToken));
  check(res, { 'export answers 200': (r) => r.status === 200 });
  return res.status === 200 ? res.json() : [];
}
