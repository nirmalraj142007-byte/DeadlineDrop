# Cloud Run Request Buffering Probe

Empirical measurement to determine whether Google Cloud Run's front end streams slow multipart upload request bodies to the container as they arrive or buffers them completely before forwarding.

- **Target Service**: `dd-arm-a` (`asia-south1`, Cloud Run gen2)
- **Payload**: 20 MB random PDF (`20,971,520` bytes)
- **Client Rate Limit**: 125 kB/s (~1 Mbps) via `curl --limit-rate 125k`
- **Request ID**: `f6ca69e2-3c92-415d-81dd-7c27da5a5308`

## Timing Table

| Event | Epoch (ms) | Seconds since clientStart |
| :--- | :--- | :--- |
| `clientStart` | `1794397521864` | `0.000` |
| `request_arrived` | `1794397686418` | `164.554` |
| `body_complete` | `1794397686553` | `164.689` |
| `stored` | `1794397687432` | `165.568` |
| `clientEnd` | `1794397687245` | `165.381` |

## Conclusion

Because `request_arrived` occurred at 164.554 s (near `clientEnd` at 165.381 s, rather than within about 2 s of `clientStart`), Cloud Run buffers first.

clientStart/clientEnd come from the laptop clock and the other events from Cloud Run's clock; they can differ by up to about 1 s, which explains stored appearing after clientEnd and does not affect the 160 s conclusion.

## Raw Log Lines

```json
[
  {
    "insertId": "6af455f6000665bf2f1505ed",
    "jsonPayload": {
      "@timestamp": "2026-11-11T11:48:06.418517570Z",
      "contentLength": 20971724,
      "ecs": {
        "version": "8.11"
      },
      "epochMs": 1794397686418,
      "event": "request_arrived",
      "log": {
        "level": "INFO",
        "logger": "com.deadlinedrop.web.RequestTimingLogFilter"
      },
      "message": "request_arrived",
      "process": {
        "pid": 1,
        "thread": {
          "name": "http-nio-8080-exec-1"
        }
      },
      "requestId": "f6ca69e2-3c92-415d-81dd-7c27da5a5308",
      "service": {
        "name": "deadlinedrop",
        "node": {},
        "version": "0.0.1-SNAPSHOT"
      }
    },
    "labels": {
      "instanceId": "00e8a408bdf90f3b9e865f3be4c0c5b15063ff45de3c167e04ca0fa82b43fc29f96c626961255a680a1d8ad68bb92271db665f83b2850402bdae9a189136bf8de572f3d0843cdfc24fad9eb881378c8eed5443155a1973421651df"
    },
    "logName": "projects/deadlinedrop-nirmal/logs/run.googleapis.com%2Fstdout",
    "receiveTimestamp": "2026-11-11T11:48:06.423000032Z",
    "resource": {
      "labels": {
        "configuration_name": "dd-arm-a",
        "location": "asia-south1",
        "project_id": "deadlinedrop-nirmal",
        "revision_name": "dd-arm-a-00006-rjv",
        "service_name": "dd-arm-a"
      },
      "type": "cloud_run_revision"
    },
    "timestamp": "2026-11-11T11:48:06.419263Z"
  },
  {
    "insertId": "6af455f60008744fe2ffb44f",
    "jsonPayload": {
      "@timestamp": "2026-11-11T11:48:06.553296747Z",
      "ecs": {
        "version": "8.11"
      },
      "epochMs": 1794397686553,
      "event": "body_complete",
      "log": {
        "level": "INFO",
        "logger": "com.deadlinedrop.api.NaiveSubmissionController"
      },
      "message": "body_complete",
      "process": {
        "pid": 1,
        "thread": {
          "name": "http-nio-8080-exec-1"
        }
      },
      "requestId": "f6ca69e2-3c92-415d-81dd-7c27da5a5308",
      "service": {
        "name": "deadlinedrop",
        "node": {},
        "version": "0.0.1-SNAPSHOT"
      }
    },
    "labels": {
      "instanceId": "00e8a408bdf90f3b9e865f3be4c0c5b15063ff45de3c167e04ca0fa82b43fc29f96c626961255a680a1d8ad68bb92271db665f83b2850402bdae9a189136bf8de572f3d0843cdfc24fad9eb881378c8eed5443155a1973421651df"
    },
    "logName": "projects/deadlinedrop-nirmal/logs/run.googleapis.com%2Fstdout",
    "receiveTimestamp": "2026-11-11T11:48:06.758175013Z",
    "resource": {
      "labels": {
        "configuration_name": "dd-arm-a",
        "location": "asia-south1",
        "project_id": "deadlinedrop-nirmal",
        "revision_name": "dd-arm-a-00006-rjv",
        "service_name": "dd-arm-a"
      },
      "type": "cloud_run_revision"
    },
    "timestamp": "2026-11-11T11:48:06.554063Z"
  },
  {
    "insertId": "6af455f700069daf127a3b69",
    "jsonPayload": {
      "@timestamp": "2026-11-11T11:48:07.432758833Z",
      "ecs": {
        "version": "8.11"
      },
      "epochMs": 1794397687432,
      "event": "stored",
      "log": {
        "level": "INFO",
        "logger": "com.deadlinedrop.api.NaiveSubmissionController"
      },
      "message": "stored",
      "process": {
        "pid": 1,
        "thread": {
          "name": "http-nio-8080-exec-1"
        }
      },
      "requestId": "f6ca69e2-3c92-415d-81dd-7c27da5a5308",
      "service": {
        "name": "deadlinedrop",
        "node": {},
        "version": "0.0.1-SNAPSHOT"
      }
    },
    "labels": {
      "instanceId": "00e8a408bdf90f3b9e865f3be4c0c5b15063ff45de3c167e04ca0fa82b43fc29f96c626961255a680a1d8ad68bb92271db665f83b2850402bdae9a189136bf8de572f3d0843cdfc24fad9eb881378c8eed5443155a1973421651df"
    },
    "logName": "projects/deadlinedrop-nirmal/logs/run.googleapis.com%2Fstdout",
    "receiveTimestamp": "2026-11-11T11:48:07.757389021Z",
    "resource": {
      "labels": {
        "configuration_name": "dd-arm-a",
        "location": "asia-south1",
        "project_id": "deadlinedrop-nirmal",
        "revision_name": "dd-arm-a-00006-rjv",
        "service_name": "dd-arm-a"
      },
      "type": "cloud_run_revision"
    },
    "timestamp": "2026-11-11T11:48:07.433583Z"
  }
]
```
