import crypto from 'k6/crypto';
import http from 'k6/http';
import { generateUuid, UPLOAD_TIMEOUT } from './lib/flows.js';

// One student through arm C from a (shaped) k6 VM. Measures:
//   rttMs        median TCP connect time of RTT_SAMPLES fresh connections to GET /api/time
//                (a SYN/SYN-ACK round trip: network only, no server work)
//   throughput   20 MB PUT to the signed URL, in decimal Mbit/s (the unit tc uses)
// The last line is "SANITY {json}" so tools/vm/sanity.sh can check it against cohorts.env.
export const options = {
  vus: 1,
  iterations: 1,
  noConnectionReuse: true,
};

const RTT_SAMPLES = 7;

function median(values) {
  const s = values.slice().sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

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

  // 2. Network RTT: TCP connect time of fresh connections
  const connects = [];
  for (let i = 0; i < RTT_SAMPLES; i++) {
    const r = http.get(`${baseUrl}/api/time`);
    if (r.status !== 200) {
      throw new Error(`GET /api/time failed: status ${r.status}`);
    }
    connects.push(r.timings.connecting);
  }
  const rttMs = median(connects);

  // 3. Prepare 20 MB file
  const sizeBytes = 20 * 1024 * 1024;
  const fileBytes = new Uint8Array(sizeBytes).buffer;
  const sha256 = crypto.sha256(fileBytes, 'hex');

  // 4. Commit step (measure commit RTT, includes server work)
  const commitRes = http.post(
    `${baseUrl}/api/assignments/${assignmentId}/commits`,
    JSON.stringify({ sha256: sha256, sizeBytes: sizeBytes, contentType: 'application/pdf' }),
    {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': generateUuid(),
      },
    }
  );
  if (commitRes.status !== 200 && commitRes.status !== 201) {
    throw new Error(`Commit failed: status ${commitRes.status} body: ${commitRes.body}`);
  }
  const commitJson = commitRes.json();
  const submissionId = commitJson.submissionId || commitJson.id;
  const headers = Object.assign({}, commitJson.uploadHeaders || {});
  if (!headers['Content-Type']) {
    headers['Content-Type'] = 'application/pdf';
  }

  // 5. PUT bytes to the signed URL
  const putStart = Date.now();
  const putRes = http.put(commitJson.uploadUrl, fileBytes, { headers, timeout: UPLOAD_TIMEOUT });
  const putMs = Date.now() - putStart;
  if (putRes.status !== 200) {
    throw new Error(`PUT failed: status ${putRes.status} body: ${putRes.body}`);
  }
  const throughputMbps = (sizeBytes * 8) / 1e6 / (putMs / 1000);

  // 6. Finalize
  const finRes = http.post(`${baseUrl}/api/submissions/${submissionId}/finalize`, '', {
    headers: { 'Authorization': `Bearer ${token}`, 'Content-Length': '0' },
  });
  if (finRes.status !== 200 && finRes.status !== 201) {
    throw new Error(`Finalize failed: status ${finRes.status} body: ${finRes.body}`);
  }

  console.log(`Sanity arm C: file ${sizeBytes} bytes`);
  console.log(`  TCP connect RTT samples (ms): ${connects.map((c) => c.toFixed(1)).join(', ')}`);
  console.log(`  RTT median: ${rttMs.toFixed(1)} ms`);
  console.log(`  Commit request: ${commitRes.timings.duration.toFixed(1)} ms (Server-Timing: ${commitRes.headers['Server-Timing']})`);
  console.log(`  PUT: ${putMs} ms, throughput ${throughputMbps.toFixed(3)} Mbit/s`);
  console.log(`  Finalize status: ${finRes.status}`);
  console.log('SANITY ' + JSON.stringify({
    rttMs: Number(rttMs.toFixed(1)),
    rttSamplesMs: connects.map((c) => Number(c.toFixed(1))),
    throughputMbps: Number(throughputMbps.toFixed(3)),
    putMs: putMs,
    commitMs: Number(commitRes.timings.duration.toFixed(1)),
    serverTiming: commitRes.headers['Server-Timing'] || null,
  }));
}
