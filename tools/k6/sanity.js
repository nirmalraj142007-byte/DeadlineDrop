import crypto from 'k6/crypto';
import http from 'k6/http';
import { generateUuid } from './lib/flows.js';

export const options = {
  vus: 1,
  iterations: 1,
};

export default function () {
  const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
  const assignmentId = __ENV.ASSIGNMENT_ID || '11111111-1111-1111-1111-111111111111';
  const email = __ENV.EMAIL || 'student0001@dd.local';
  const password = __ENV.PASSWORD || 'secret';

  // 1. Login
  const loginRes = http.post(
    `${baseUrl}/api/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  if (loginRes.status !== 200) {
    throw new Error(`Login failed for ${email}: status ${loginRes.status} body: ${loginRes.body}`);
  }
  const token = loginRes.json('accessToken');

  // 2. Prepare 20 MB file
  const sizeMb = 20;
  const sizeBytes = sizeMb * 1024 * 1024;
  const fileBytes = new Uint8Array(sizeBytes).buffer;
  const sha256 = crypto.sha256(fileBytes, 'hex');

  // 3. Commit step (measure commit RTT)
  const idempotencyKey = generateUuid();
  const commitStart = Date.now();
  const commitRes = http.post(
    `${baseUrl}/api/assignments/${assignmentId}/commits`,
    JSON.stringify({
      sha256: sha256,
      sizeBytes: sizeBytes,
      contentType: 'application/pdf',
    }),
    {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
    }
  );
  const commitRttMs = Date.now() - commitStart;

  if (commitRes.status !== 200 && commitRes.status !== 201) {
    throw new Error(`Commit failed: status ${commitRes.status} body: ${commitRes.body}`);
  }

  const commitJson = commitRes.json();
  const submissionId = commitJson.submissionId || commitJson.id;
  const uploadUrl = commitJson.uploadUrl;
  const uploadHeaders = commitJson.uploadHeaders || {};

  // 4. PUT bytes to signed URL (measure duration & throughput)
  const headers = Object.assign({}, uploadHeaders);
  if (!headers['Content-Type']) {
    headers['Content-Type'] = 'application/pdf';
  }
  const putStart = Date.now();
  const putRes = http.put(uploadUrl, fileBytes, { headers });
  const putMs = Date.now() - putStart;

  if (putRes.status !== 200) {
    throw new Error(`PUT failed: status ${putRes.status} body: ${putRes.body}`);
  }

  const throughputMbps = ((sizeMb * 8) / (putMs / 1000)).toFixed(2);

  // 5. Finalize
  const finRes = http.post(
    `${baseUrl}/api/submissions/${submissionId}/finalize`,
    '',
    {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Length': '0',
      },
    }
  );

  if (finRes.status !== 200 && finRes.status !== 201) {
    throw new Error(`Finalize failed: status ${finRes.status} body: ${finRes.body}`);
  }

  console.log(`Sanity Arm C Check:`);
  console.log(`  File size: ${sizeMb} MB`);
  console.log(`  Commit RTT: ${commitRttMs} ms`);
  console.log(`  PUT duration: ${putMs} ms`);
  console.log(`  Throughput: ${throughputMbps} Mbps`);
  console.log(`  Submission ID: ${submissionId}`);
  console.log(`  Finalize status: ${finRes.status}`);
}
