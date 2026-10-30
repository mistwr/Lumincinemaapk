# LUMIN Studio generation backend contract

The Flutter app expects a separately deployed authenticated service. This contract does not activate a provider or spend money.

## POST /v1/generation/jobs
Authorization: Bearer <user access token>
Content-Type: application/json

Request:
```json
{"type":"image","prompt":"a golden robot","aspect_ratio":"9:16"}
```
Response (202):
```json
{"id":"job_123","status":"queued"}
```

## GET /v1/generation/jobs/{id}
Authorization: Bearer <user access token>

Response (200):
```json
{"id":"job_123","status":"completed","result_url":"https://example.com/signed-result"}
```

Status must be one of queued, running, completed, failed, cancelled.
The backend must enforce user ownership, validate input, rate limit, control spend, and generate expiring download URLs. Provider API keys must never be sent to the Android client.

## Not yet implemented
Provider adapters, deployment, JWT verification, database, async worker, job polling, and real image/video rendering. No endpoint is currently claimed to be live.
