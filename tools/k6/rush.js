import * as fs from 'k6/experimental/fs';
import crypto from 'k6/crypto';
import http from 'k6/http';
import { sleep } from 'k6';
import { armC, armB, armA } from './lib/flows.js';

const schedule = JSON.parse(open(__ENV.SHARD));
const fileHandles = {};
const fileBuffers = {};
const fileHashes = {};
const BUCKETS = [1, 2, 5, 10, 15, 25];

(async () => {
  const dir = __ENV.FILES_DIR || 'tools/k6/files/out';
  for (const sz of BUCKETS) {
    try {
      fileHandles[sz] = await fs.open(`${dir}/${sz}mb.bin`);
    } catch (e) {
      // Bucket file might not exist if capped by MAX_MB
    }
  }
})();

async function loadFile(sz) {
  if (fileBuffers[sz]) {
    return { bytes: fileBuffers[sz], hash: fileHashes[sz] };
  }
  const handle = fileHandles[sz];
  if (!handle) {
    throw new Error(`File handle for bucket ${sz}MB not found`);
  }
  const stat = await handle.stat();
  const buf = new Uint8Array(stat.size);
  await handle.seek(0, fs.SeekMode.Start);
  await handle.read(buf);
  fileBuffers[sz] = buf.buffer;
  fileHashes[sz] = crypto.sha256(buf.buffer, 'hex');
  return { bytes: fileBuffers[sz], hash: fileHashes[sz] };
}

const cohortMinutes = parseFloat(__ENV.COHORT_MINUTES || '10');
const graceSeconds = parseFloat(__ENV.GRACE_S || '300');
const tEpoch = parseInt(__ENV.T_EPOCH_MS || '0', 10);
const timeUntilDeadlineS = tEpoch > Date.now() ? Math.ceil((tEpoch - Date.now()) / 1000) : 0;
const maxDurationSeconds = timeUntilDeadlineS + Math.ceil(cohortMinutes * 60 + graceSeconds + 300);

export const options = {
  scenarios: {
    rush: {
      executor: 'per-vu-iterations',
      vus: schedule.length,
      iterations: 1,
      maxDuration: `${maxDurationSeconds}s`,
    },
  },
};

export function setup() {
  const tokenMap = {};
  const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
  const password = __ENV.PASSWORD || 'secret';
  const intervalMs = 50; // At most 20 logins per second

  for (let i = 0; i < schedule.length; i++) {
    const student = schedule[i];
    const start = Date.now();
    const res = http.post(
      `${baseUrl}/api/auth/login`,
      JSON.stringify({
        email: student.email,
        password: password,
      }),
      {
        headers: { 'Content-Type': 'application/json' },
      }
    );
    if (res.status === 200) {
      tokenMap[student.email] = res.json('accessToken');
    } else {
      console.error(`Login failed for ${student.email}: status ${res.status}`);
    }
    const elapsed = Date.now() - start;
    if (elapsed < intervalMs) {
      sleep((intervalMs - elapsed) / 1000);
    }
  }
  return { tokenMap };
}

export default async function (data) {
  const vuIndex = __VU - 1;
  const student = schedule[vuIndex];
  if (!student) {
    return;
  }

  const tEpochMs = parseInt(__ENV.T_EPOCH_MS || '0', 10);
  const graceSeconds = parseInt(__ENV.GRACE_S || '300', 10);
  const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
  const assignmentId = __ENV.ASSIGNMENT_ID || '11111111-1111-1111-1111-111111111111';
  const arm = (__ENV.ARM || 'c').toLowerCase();

  // Sleep until T + clickOffsetMs
  const targetClickEpochMs = tEpochMs + student.clickOffsetMs;
  const now = Date.now();
  const waitMs = targetClickEpochMs - now;
  if (waitMs > 0) {
    sleep(waitMs / 1000);
  }
  const clickMs = Date.now();

  // Sleep hashMs (arm C only)
  let hashWaitMs = 0;
  if (arm === 'c') {
    hashWaitMs = student.hashMs || 0;
    if (hashWaitMs > 0) {
      sleep(hashWaitMs / 1000);
    }
  }

  const { bytes, hash } = await loadFile(student.sizeBucketMb);
  const token = (data && data.tokenMap) ? data.tokenMap[student.email] : null;

  const ctx = {
    student,
    token,
    clickMs,
    hashWaitMs,
    deadlineEpochMs: tEpochMs,
    graceSeconds,
    assignmentId,
    baseUrl,
    fileBytes: bytes,
    fileSha256: hash,
  };

  if (arm === 'c') {
    armC(ctx);
  } else if (arm === 'b') {
    armB(ctx);
  } else if (arm === 'a') {
    armA(ctx);
  } else {
    throw new Error(`Unknown ARM: ${arm}`);
  }
}

export function handleSummary(data) {
  return {
    'summary.json': JSON.stringify(data, null, 2),
  };
}
