import http from 'k6/http';
import { sleep } from 'k6';

export function generateUuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function shouldRetry(status) {
  if (status === 0 || status === 429) return true;
  if (status >= 500 && status <= 599) return true;
  return false;
}

export function calculateJitterMs(attempt, baseMs = 500, capMs = 8000) {
  const maxWait = Math.min(capMs, baseMs * Math.pow(2, attempt));
  return Math.random() * maxWait;
}

export function armC(ctx) {
  const {
    student,
    token,
    clickMs,
    hashWaitMs = 0,
    deadlineEpochMs,
    graceSeconds,
    assignmentId,
    baseUrl,
    fileBytes,
    fileSha256,
  } = ctx;

  let commitStatus = null;
  let commitAttempts = 0;
  let putStatus = null;
  let putMs = null;
  let finalizeStatus = null;
  let finalizeAttempts = 0;
  let submissionId = null;
  let uploadUrl = null;
  let uploadHeaders = {};
  let error = null;
  const skipFinalize = Boolean(student.skipFinalize);

  const cutoffMs = deadlineEpochMs + graceSeconds * 1000;
  const idempotencyKey = generateUuid();

  // 1. Commit step
  while (true) {
    if (Date.now() >= cutoffMs) {
      error = error || 'deadline_grace_expired_before_commit';
      break;
    }
    commitAttempts++;
    const res = http.post(
      `${baseUrl}/api/assignments/${assignmentId}/commits`,
      JSON.stringify({
        sha256: fileSha256,
        sizeBytes: fileBytes.byteLength,
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
    commitStatus = res.status;
    if (commitStatus === 200 || commitStatus === 201) {
      try {
        const json = res.json();
        submissionId = json.submissionId || json.id;
        uploadUrl = json.uploadUrl;
        uploadHeaders = json.uploadHeaders || {};
      } catch (e) {
        error = 'commit_json_parse_error';
      }
      break;
    }
    if (!shouldRetry(commitStatus)) {
      error = `commit_failed_status_${commitStatus}`;
      break;
    }
    const jitterMs = calculateJitterMs(commitAttempts - 1);
    if (Date.now() + jitterMs >= cutoffMs) {
      error = 'deadline_grace_expired_during_commit_backoff';
      break;
    }
    sleep(jitterMs / 1000);
  }

  // 2. PUT step
  if (uploadUrl) {
    const headers = Object.assign({}, uploadHeaders);
    if (!headers['Content-Type']) {
      headers['Content-Type'] = 'application/pdf';
    }
    const putStart = Date.now();
    const putRes = http.put(uploadUrl, fileBytes, { headers: headers });
    putMs = Date.now() - putStart;
    putStatus = putRes.status;

    if (putStatus !== 200) {
      error = `put_failed_status_${putStatus}`;
    } else if (!skipFinalize) {
      // 3. Finalize step
      while (true) {
        if (Date.now() >= cutoffMs) {
          error = error || 'deadline_grace_expired_before_finalize';
          break;
        }
        finalizeAttempts++;
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
        finalizeStatus = finRes.status;
        if (finalizeStatus === 200 || finalizeStatus === 201) {
          break;
        }
        if (!shouldRetry(finalizeStatus)) {
          error = `finalize_failed_status_${finalizeStatus}`;
          break;
        }
        const jitterMs = calculateJitterMs(finalizeAttempts - 1);
        if (Date.now() + jitterMs >= cutoffMs) {
          error = 'deadline_grace_expired_during_finalize_backoff';
          break;
        }
        sleep(jitterMs / 1000);
      }
    }
  }

  const logLine = {
    email: student.email,
    cohort: student.cohort,
    plannedClass: student.plannedClass,
    clickMs: clickMs,
    hashWaitMs: hashWaitMs,
    commitStatus: commitStatus,
    commitAttempts: commitAttempts,
    putStatus: putStatus,
    putMs: putMs,
    finalizeStatus: finalizeStatus,
    finalizeAttempts: finalizeAttempts,
    submissionId: submissionId,
    skipFinalize: skipFinalize,
    error: error,
  };
  console.log(JSON.stringify(logLine));
  return logLine;
}

export function armB(ctx) {
  const {
    student,
    token,
    clickMs,
    hashWaitMs = 0,
    deadlineEpochMs,
    graceSeconds,
    assignmentId,
    baseUrl,
    fileBytes,
  } = ctx;

  let commitStatus = null;
  let commitAttempts = 0;
  let putStatus = null;
  let putMs = null;
  let finalizeStatus = null;
  let finalizeAttempts = 0;
  let submissionId = null;
  let uploadId = null;
  let uploadUrl = null;
  let uploadHeaders = {};
  let error = null;
  const skipFinalize = Boolean(student.skipFinalize);

  const cutoffMs = deadlineEpochMs + graceSeconds * 1000;

  // 1. Direct-uploads creation step
  while (true) {
    if (Date.now() >= cutoffMs) {
      error = error || 'deadline_grace_expired_before_direct_upload';
      break;
    }
    commitAttempts++;
    const res = http.post(
      `${baseUrl}/api/assignments/${assignmentId}/direct-uploads`,
      JSON.stringify({
        sizeBytes: fileBytes.byteLength,
        contentType: 'application/pdf',
      }),
      {
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json',
        },
      }
    );
    commitStatus = res.status;
    if (commitStatus === 200 || commitStatus === 201) {
      try {
        const json = res.json();
        uploadId = json.uploadId || json.id;
        uploadUrl = json.uploadUrl;
        uploadHeaders = json.uploadHeaders || {};
      } catch (e) {
        error = 'direct_upload_json_parse_error';
      }
      break;
    }
    if (!shouldRetry(commitStatus)) {
      error = `direct_upload_failed_status_${commitStatus}`;
      break;
    }
    const jitterMs = calculateJitterMs(commitAttempts - 1);
    if (Date.now() + jitterMs >= cutoffMs) {
      error = 'deadline_grace_expired_during_direct_upload_backoff';
      break;
    }
    sleep(jitterMs / 1000);
  }

  // 2. PUT step
  if (uploadUrl) {
    const headers = Object.assign({}, uploadHeaders);
    if (!headers['Content-Type']) {
      headers['Content-Type'] = 'application/pdf';
    }
    const putStart = Date.now();
    const putRes = http.put(uploadUrl, fileBytes, { headers: headers });
    putMs = Date.now() - putStart;
    putStatus = putRes.status;

    if (putStatus !== 200) {
      error = `put_failed_status_${putStatus}`;
    } else if (!skipFinalize) {
      // 3. Finalize step
      while (true) {
        if (Date.now() >= cutoffMs) {
          error = error || 'deadline_grace_expired_before_finalize';
          break;
        }
        finalizeAttempts++;
        const finRes = http.post(
          `${baseUrl}/api/assignments/${assignmentId}/direct-uploads/${uploadId}/finalize`,
          '',
          {
            headers: {
              'Authorization': `Bearer ${token}`,
              'Content-Length': '0',
            },
          }
        );
        finalizeStatus = finRes.status;
        if (finalizeStatus === 200 || finalizeStatus === 201) {
          try {
            const json = finRes.json();
            submissionId = json.submissionId || json.id;
          } catch (e) {
            // Ignored if response is empty
          }
          break;
        }
        if (!shouldRetry(finalizeStatus)) {
          error = `finalize_failed_status_${finalizeStatus}`;
          break;
        }
        const jitterMs = calculateJitterMs(finalizeAttempts - 1);
        if (Date.now() + jitterMs >= cutoffMs) {
          error = 'deadline_grace_expired_during_finalize_backoff';
          break;
        }
        sleep(jitterMs / 1000);
      }
    }
  }

  const logLine = {
    email: student.email,
    cohort: student.cohort,
    plannedClass: student.plannedClass,
    clickMs: clickMs,
    hashWaitMs: hashWaitMs,
    commitStatus: commitStatus,
    commitAttempts: commitAttempts,
    putStatus: putStatus,
    putMs: putMs,
    finalizeStatus: finalizeStatus,
    finalizeAttempts: finalizeAttempts,
    submissionId: submissionId,
    skipFinalize: skipFinalize,
    error: error,
  };
  console.log(JSON.stringify(logLine));
  return logLine;
}

export function armA(ctx) {
  const {
    student,
    token,
    clickMs,
    hashWaitMs = 0,
    deadlineEpochMs,
    graceSeconds,
    assignmentId,
    baseUrl,
    fileBytes,
  } = ctx;

  let commitStatus = null;
  let commitAttempts = 0;
  let putStatus = null;
  let putMs = null;
  let finalizeStatus = null;
  let finalizeAttempts = 0;
  let submissionId = null;
  let error = null;
  const skipFinalize = Boolean(student.skipFinalize);

  const cutoffMs = deadlineEpochMs + graceSeconds * 1000;
  const idempotencyKey = generateUuid();

  while (true) {
    if (Date.now() >= cutoffMs) {
      error = error || 'deadline_grace_expired_before_naive_submit';
      break;
    }
    commitAttempts++;
    finalizeAttempts++;
    const putStart = Date.now();
    const data = {
      file: http.file(fileBytes, 'submission.pdf', 'application/pdf'),
    };
    const res = http.post(
      `${baseUrl}/api/assignments/${assignmentId}/naive-submissions`,
      data,
      {
        headers: {
          'Authorization': `Bearer ${token}`,
          'Idempotency-Key': idempotencyKey,
        },
      }
    );
    putMs = Date.now() - putStart;
    commitStatus = res.status;
    putStatus = res.status;
    finalizeStatus = res.status;

    if (res.status === 200 || res.status === 201) {
      try {
        const json = res.json();
        submissionId = json.submissionId || json.id;
      } catch (e) {
        error = 'naive_submit_json_parse_error';
      }
      break;
    }
    if (!shouldRetry(res.status)) {
      error = `naive_submit_failed_status_${res.status}`;
      break;
    }
    const jitterMs = calculateJitterMs(commitAttempts - 1);
    if (Date.now() + jitterMs >= cutoffMs) {
      error = 'deadline_grace_expired_during_naive_submit_backoff';
      break;
    }
    sleep(jitterMs / 1000);
  }

  const logLine = {
    email: student.email,
    cohort: student.cohort,
    plannedClass: student.plannedClass,
    clickMs: clickMs,
    hashWaitMs: hashWaitMs,
    commitStatus: commitStatus,
    commitAttempts: commitAttempts,
    putStatus: putStatus,
    putMs: putMs,
    finalizeStatus: finalizeStatus,
    finalizeAttempts: finalizeAttempts,
    submissionId: submissionId,
    skipFinalize: skipFinalize,
    error: error,
  };
  console.log(JSON.stringify(logLine));
  return logLine;
}
