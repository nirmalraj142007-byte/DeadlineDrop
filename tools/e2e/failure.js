// End-to-end failure path: 5 students click at the same moment while tools/e2e/failure.sh has
// paused Postgres. Every student uses the shared retry policy (tools/k6/lib/flows.js armC).
// Afterwards, every student is VERIFIED with exactly one live row, and at least one student
// met a 503 and still finished.
import { check, fail, sleep } from 'k6';
import crypto from 'k6/crypto';
import { Counter } from 'k6/metrics';
import { armC } from '../k6/lib/flows.js';
import {
  BASE_URL, TEACHER_EMAIL, createAssignment, exportRows, login, randomFile, requirePassword, sleepUntil,
} from './lib.js';

const STUDENTS = 5;
const FILE_BYTES = 2 * 1024 * 1024;
const GRACE_SECONDS = 60;
const SETTLE_LIMIT_S = 180;
const CLICK_LEAD_MS = 5000;

const retried503 = new Counter('e2e_students_retried_503');

export const options = {
  scenarios: {
    rush: { executor: 'per-vu-iterations', vus: STUDENTS, iterations: 1, maxDuration: '10m' },
  },
  thresholds: { checks: ['rate==1'], e2e_students_retried_503: ['count>=1'] },
  setupTimeout: '120s',
  teardownTimeout: '240s',
};

export function setup() {
  requirePassword();
  const teacher = login(TEACHER_EMAIL);
  const deadlineMs = Date.now() + 60 * 60 * 1000;
  const assignmentId = createAssignment(teacher, `e2e failure ${new Date().toISOString()}`, deadlineMs,
    GRACE_SECONDS);
  const students = [];
  for (let i = 2; i < 2 + STUDENTS; i++) {
    const email = `student${String(i).padStart(4, '0')}@dd.local`;
    students.push({ email: email, token: login(email) });
  }
  // Logins need the database, so they happen before the click. failure.sh watches for this line
  // and pauses Postgres; the lead lets the pooled connections go idle, so the first request
  // after the click meets a database that is already gone.
  const clickAtMs = Date.now() + CLICK_LEAD_MS;
  console.log(`E2E_CLICK_AT ${clickAtMs} students=${STUDENTS} assignment=${assignmentId}`);
  return { assignmentId: assignmentId, deadlineMs: deadlineMs, students: students, clickAtMs: clickAtMs };
}

export default function (data) {
  const student = data.students[__VU - 1];
  const file = randomFile(FILE_BYTES);
  const sha256 = crypto.sha256(file, 'hex');
  sleepUntil(data.clickAtMs, sleep);
  const log = armC({
    student: { email: student.email, cohort: 'e2e', plannedClass: 'e2e' },
    token: student.token,
    clickMs: data.clickAtMs,
    deadlineEpochMs: data.deadlineMs,
    graceSeconds: GRACE_SECONDS,
    assignmentId: data.assignmentId,
    baseUrl: BASE_URL,
    fileBytes: file,
    fileSha256: sha256,
  });
  check(log, { 'the student finished commit, upload and finalize': (l) => l.error === null });
  if (log.failedStatuses.some((f) => f.status === 503) && log.error === null) {
    retried503.add(1);
  }
}

export function teardown(data) {
  const teacher = login(TEACHER_EMAIL);
  let rows = [];
  for (let waited = 0; waited <= SETTLE_LIMIT_S; waited += 5) {
    rows = exportRows(teacher, data.assignmentId);
    if (rows.length >= STUDENTS && rows.every((r) => r.status === 'VERIFIED')) {
      break;
    }
    sleep(5);
  }
  const live = rows.filter((r) => r.status !== 'EXPIRED' && r.status !== 'SUPERSEDED');
  check(live, {
    'every student has a live row': (l) => data.students.every((s) => l.some((r) => r.studentEmail === s.email)),
    'exactly one live row per student': (l) => data.students.every(
      (s) => l.filter((r) => r.studentEmail === s.email).length === 1),
    'every live row is VERIFIED': (l) => l.length === STUDENTS && l.every((r) => r.status === 'VERIFIED'),
  });
  if (live.length === 0) {
    fail('no live rows were exported');
  }
}
