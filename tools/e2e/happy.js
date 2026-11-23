// End-to-end happy path: one student submits a 2 MB file, a teacher sees it VERIFIED and
// downloads exactly the committed bytes. Any failed check exits non-zero (see thresholds).
import http from 'k6/http';
import crypto from 'k6/crypto';
import { check, fail, sleep } from 'k6';
import { generateUuid, UPLOAD_TIMEOUT } from '../k6/lib/flows.js';
import {
  BASE_URL, CONTENT_TYPE, TEACHER_EMAIL, bearer, createAssignment, login, randomFile, requirePassword,
} from './lib.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] },
};

const FILE_BYTES = 2 * 1024 * 1024;
const POLL_EVERY_S = 5;
const POLL_LIMIT_S = 150;

export default function () {
  requirePassword();
  const teacher = login(TEACHER_EMAIL);
  const assignmentId = createAssignment(teacher, `e2e happy ${new Date().toISOString()}`,
    Date.now() + 60 * 60 * 1000, 60);
  const student = login('student0001@dd.local');

  const file = randomFile(FILE_BYTES);
  const sha256 = crypto.sha256(file, 'hex');

  const commit = http.post(`${BASE_URL}/api/assignments/${assignmentId}/commits`,
    JSON.stringify({ sha256: sha256, sizeBytes: FILE_BYTES, contentType: CONTENT_TYPE }),
    bearer(student, { 'Content-Type': 'application/json', 'Idempotency-Key': generateUuid() }));
  check(commit, { 'commit answers 201': (r) => r.status === 201 });
  if (commit.status !== 201) {
    fail(`commit failed: ${commit.status} ${commit.body}`);
  }
  const committed = commit.json();
  check(committed, {
    'commit is stamped on arrival': (c) => c.status === 'COMMITTED' && Boolean(c.committedAt),
    'commit carries a signed upload URL': (c) => Boolean(c.uploadUrl),
  });

  const headers = Object.assign({ 'Content-Type': CONTENT_TYPE }, committed.uploadHeaders || {});
  const put = http.put(committed.uploadUrl, file, { headers: headers, timeout: UPLOAD_TIMEOUT });
  check(put, { 'upload to the signed URL answers 200': (r) => r.status === 200 });

  const finalize = http.post(`${BASE_URL}/api/submissions/${committed.submissionId}/finalize`, '',
    bearer(student, { 'Content-Length': '0' }));
  check(finalize, { 'finalize answers 200': (r) => r.status === 200 });

  let status = null;
  for (let waited = 0; waited <= POLL_LIMIT_S; waited += POLL_EVERY_S) {
    const res = http.get(`${BASE_URL}/api/submissions/${committed.submissionId}`, bearer(student));
    status = res.status === 200 ? res.json('status') : `HTTP ${res.status}`;
    if (status === 'VERIFIED') {
      break;
    }
    sleep(POLL_EVERY_S);
  }
  check(status, { 'submission is VERIFIED within 150 s': (s) => s === 'VERIFIED' });

  const dash = http.get(`${BASE_URL}/api/assignments/${assignmentId}/dashboard`, bearer(teacher));
  check(dash, { 'dashboard answers 200': (r) => r.status === 200 });
  const row = dash.status === 200
    ? dash.json('rows').filter((r) => r.submissionId === committed.submissionId)[0] : null;
  check(row, {
    'dashboard has the row': (r) => Boolean(r),
    'dashboard row is VERIFIED': (r) => Boolean(r) && r.status === 'VERIFIED',
  });

  const download = http.get(`${BASE_URL}/api/submissions/${committed.submissionId}/download`, bearer(teacher));
  check(download, { 'download URL answers 200': (r) => r.status === 200 });
  if (download.status === 200) {
    const bytes = http.get(download.json('url'), { responseType: 'binary', timeout: UPLOAD_TIMEOUT });
    check(bytes, { 'the signed download URL answers 200': (r) => r.status === 200 });
    check(bytes, {
      'downloaded bytes hash to the committed hash': (r) => crypto.sha256(r.body, 'hex') === sha256,
    });
  }
}
