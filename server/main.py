"""
수집 엔드포인트 (Cloud Run functions / Python 3.12).

앱이 BigQuery에 직접 쓰지 않는 이유는 하나다. 그러려면 서비스 계정 키를
APK에 넣어야 하는데, APK는 언제든 디컴파일된다. 키 하나 유출되면
웨어하우스 쓰기 권한이 통째로 나간다. 단말은 이 엔드포인트로만 보내고,
BigQuery 권한은 함수의 서비스 계정에만 준다.

배포:
  gcloud functions deploy wifi-delivery-ingest \
    --gen2 --runtime=python312 --region=asia-northeast3 \
    --source=. --entry-point=ingest --trigger-http \
    --no-allow-unauthenticated \
    --set-env-vars=BQ_PROJECT=<project>,BQ_DATASET=wifi_delivery \
    --set-secrets=INGEST_API_KEY=wifi-ingest-key:latest

사내 단말만 붙는 구조라면 --no-allow-unauthenticated 로 두고
ID 토큰을 쓰는 쪽이 API 키보다 안전하다. 아래 API 키 검증은
파일럿 단계의 최소 방어선으로 생각할 것.
"""

import json
import os
from datetime import datetime, timezone

import functions_framework
from google.cloud import bigquery

BQ_PROJECT = os.environ.get("BQ_PROJECT", "")
BQ_DATASET = os.environ.get("BQ_DATASET", "wifi_delivery")
BQ_TABLE = os.environ.get("BQ_TABLE", "events_raw")
API_KEY = os.environ.get("INGEST_API_KEY", "")

MAX_EVENTS = 500

_client = bigquery.Client(project=BQ_PROJECT) if BQ_PROJECT else None

# 단말이 보낸 payload를 그대로 신뢰하지 않는다.
ALLOWED_TYPES = {
    "signature_collected",
    "match_evaluated",
    "notification_sent",
    "label_recorded",
    "tag_location_conflict",
    "advance_notice_sent",
}


def _table_id() -> str:
    return f"{BQ_PROJECT}.{BQ_DATASET}.{BQ_TABLE}"


def _to_row(event: dict, now: str) -> dict | None:
    event_type = event.get("event_type")
    if event_type not in ALLOWED_TYPES:
        return None

    ts_ms = event.get("event_ts")
    if not isinstance(ts_ms, (int, float)):
        return None
    event_ts = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc).isoformat()

    device_id = event.get("device_id")
    if not isinstance(device_id, str) or not device_id:
        return None

    # 봉투 필드를 제외한 나머지를 payload로 밀어넣는다.
    payload = {
        k: v
        for k, v in event.items()
        if k not in ("event_type", "event_ts", "device_id", "app_version")
    }

    return {
        "ingested_at": now,
        "event_ts": event_ts,
        "event_type": event_type,
        "device_id": device_id,
        "app_version": event.get("app_version"),
        "payload": json.dumps(payload, ensure_ascii=False),
    }


@functions_framework.http
def ingest(request):
    if request.method != "POST":
        return ("method not allowed", 405)

    if API_KEY and request.headers.get("X-Api-Key") != API_KEY:
        return ("unauthorized", 401)

    try:
        body = request.get_json(silent=True) or {}
    except Exception:
        return ("bad json", 400)

    events = body.get("events")
    if not isinstance(events, list):
        return ("events must be a list", 400)
    if len(events) > MAX_EVENTS:
        return (f"too many events (max {MAX_EVENTS})", 400)

    now = datetime.now(timezone.utc).isoformat()
    rows = []
    skipped = 0
    for e in events:
        if not isinstance(e, dict):
            skipped += 1
            continue
        row = _to_row(e, now)
        if row is None:
            skipped += 1
        else:
            rows.append(row)

    if not rows:
        # 앱이 큐를 비울 수 있도록 2xx로 응답한다.
        return (json.dumps({"inserted": 0, "skipped": skipped}), 200)

    if _client is None:
        return ("BQ_PROJECT not configured", 500)

    errors = _client.insert_rows_json(_table_id(), rows)
    if errors:
        # 5xx로 응답해야 앱이 큐를 유지하고 재시도한다.
        return (json.dumps({"errors": errors[:5]}, ensure_ascii=False), 503)

    return (json.dumps({"inserted": len(rows), "skipped": skipped}), 200)
