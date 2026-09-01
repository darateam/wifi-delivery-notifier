-- BigQuery 스키마
--
-- 원본 이벤트는 payload JSON 그대로 한 테이블에 착지시키고,
-- 분석용 타입 테이블은 뷰로 뽑는다. 단말 앱의 스키마가 바뀌어도
-- 적재가 깨지지 않고, 과거 데이터를 새 뷰 정의로 다시 해석할 수 있다.
--
-- 데이터셋은 서울 리전 권장: bq mk --location=asia-northeast3 wifi_delivery

CREATE SCHEMA IF NOT EXISTS `PROJECT_ID.wifi_delivery`
OPTIONS (location = 'asia-northeast3');

-- ── 착지 테이블 ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS `PROJECT_ID.wifi_delivery.events_raw` (
  ingested_at TIMESTAMP NOT NULL,
  event_ts    TIMESTAMP NOT NULL,
  event_type  STRING    NOT NULL,
  device_id   STRING    NOT NULL,
  app_version STRING,
  payload     JSON      NOT NULL
)
PARTITION BY DATE(event_ts)
CLUSTER BY event_type, device_id
OPTIONS (
  partition_expiration_days = 365,
  description = 'Wi-Fi 서명 배송알림 앱 원본 이벤트'
);

-- ── 매칭 평가 로그 ─────────────────────────────────────────────
-- 임계값 튜닝의 근거가 되는 핵심 테이블.
-- 확정된 건뿐 아니라 보류된 건까지 전부 들어 있어야
-- "임계값을 0.70으로 올렸다면 오탐이 몇 건 줄었을까"를 재수집 없이 계산할 수 있다.
CREATE OR REPLACE VIEW `PROJECT_ID.wifi_delivery.v_match_evaluated` AS
SELECT
  event_ts,
  device_id,
  app_version,
  INT64(payload.visible_ap_count)          AS visible_ap_count,
  STRING(payload.best_member_id)           AS best_member_id,
  FLOAT64(payload.best_score)              AS best_score,
  FLOAT64(payload.best_coverage)           AS best_coverage,
  FLOAT64(payload.best_rssi_score)         AS best_rssi_score,
  BOOL(payload.best_anchor_hit)            AS best_anchor_hit,
  INT64(payload.best_common_aps)           AS best_common_aps,
  STRING(payload.runner_up_member_id)      AS runner_up_member_id,
  FLOAT64(payload.runner_up_score)         AS runner_up_score,
  FLOAT64(payload.margin)                  AS margin,
  BOOL(payload.confident)                  AS confident,
  BOOL(payload.ambiguous)                  AS ambiguous,
  INT64(payload.tied_count)                AS tied_count,
  BOOL(payload.best_floor_mismatch)        AS best_floor_mismatch,
  STRING(payload.decision_reason)          AS decision_reason,
  BOOL(payload.fired)                      AS fired
FROM `PROJECT_ID.wifi_delivery.events_raw`
WHERE event_type = 'match_evaluated';

-- ── 서명 수집 로그 ─────────────────────────────────────────────
-- BSSID는 단말에서 솔트 해시로 바꿔서 올라온다. 원본 MAC은 저장하지 않는다.
CREATE OR REPLACE VIEW `PROJECT_ID.wifi_delivery.v_signature_collected` AS
SELECT
  event_ts,
  device_id,
  STRING(payload.member_id) AS member_id,
  INT64(payload.rounds)     AS rounds,
  INT64(payload.ap_count)   AS ap_count,
  STRING(ap.bssid_hash)     AS bssid_hash,
  FLOAT64(ap.rssi_mean)     AS rssi_mean,
  FLOAT64(ap.hit_ratio)     AS hit_ratio
FROM `PROJECT_ID.wifi_delivery.events_raw`,
  UNNEST(JSON_QUERY_ARRAY(payload.aps)) AS ap
WHERE event_type = 'signature_collected';

-- ── 정답 라벨 ─────────────────────────────────────────────────
-- 테스트 모드에서 사람이 직접 찍은 정답. 임계값 재탐색의 근거가 된다.
CREATE OR REPLACE VIEW `PROJECT_ID.wifi_delivery.v_label_recorded` AS
SELECT
  event_ts,
  device_id,
  STRING(payload.predicted_member_id) AS predicted_member_id,
  STRING(payload.actual_member_id)    AS actual_member_id,
  STRING(payload.verdict)             AS verdict,
  payload.params_at_label             AS params_at_label,
  CASE
    WHEN STRING(payload.verdict) = 'NOT_A_DOOR'
         AND STRING(payload.predicted_member_id) IS NOT NULL THEN 'FP'
    WHEN STRING(payload.predicted_member_id) = STRING(payload.actual_member_id) THEN 'TP'
    WHEN STRING(payload.predicted_member_id) IS NOT NULL THEN 'FP'
    WHEN STRING(payload.actual_member_id) IS NOT NULL THEN 'FN'
    ELSE 'TN'
  END AS outcome
FROM `PROJECT_ID.wifi_delivery.events_raw`
WHERE event_type = 'label_recorded';

-- ── 태그/위치 불일치 ──────────────────────────────────────────
-- 자주 뜨는 세대는 바구니가 옮겨지거나 서명이 노후한 것. 재수집 대상 신호다.
CREATE OR REPLACE VIEW `PROJECT_ID.wifi_delivery.v_tag_conflict` AS
SELECT
  event_ts,
  device_id,
  STRING(payload.member_id)   AS member_id,
  FLOAT64(payload.wifi_score) AS wifi_score,
  BOOL(payload.rival_wins)    AS rival_wins
FROM `PROJECT_ID.wifi_delivery.events_raw`
WHERE event_type = 'tag_location_conflict';

-- ── 발송 로그 ──────────────────────────────────────────────────
CREATE OR REPLACE VIEW `PROJECT_ID.wifi_delivery.v_notification_sent` AS
SELECT
  event_ts,
  device_id,
  STRING(payload.member_id)     AS member_id,
  STRING(payload.send_mode)     AS send_mode,
  BOOL(payload.success)         AS success,
  INT64(payload.dwell_seconds)  AS dwell_seconds,
  BOOL(payload.is_ad)           AS is_ad,
  STRING(payload.ad_skip_reason) AS ad_skip_reason
FROM `PROJECT_ID.wifi_delivery.events_raw`
WHERE event_type = 'notification_sent';

-- ── 방문 예정 안내 ────────────────────────────────────────────
-- is_ad = true 인 건은 광고성 문자다. 수신동의 근거를 별도로 보관해야 한다.
CREATE OR REPLACE VIEW `PROJECT_ID.wifi_delivery.v_advance_notice` AS
SELECT
  event_ts,
  device_id,
  STRING(payload.member_id)               AS member_id,
  STRING(payload.triggered_by_member_id)  AS triggered_by_member_id,
  BOOL(payload.success)                   AS success,
  BOOL(payload.is_ad)                     AS is_ad
FROM `PROJECT_ID.wifi_delivery.events_raw`
WHERE event_type = 'advance_notice_sent';


-- ══════════════════════════════════════════════════════════════
-- 튜닝용 질의
-- ══════════════════════════════════════════════════════════════

-- 1) 임계값 스윕: 점수·마진 기준을 바꿨을 때 확정 건수가 어떻게 변하는가
--    확인 모드 기간에 이걸 돌려보고 자동 발송 전환 시점을 정한다.
/*
WITH grid AS (
  SELECT s AS score_th, m AS margin_th
  FROM UNNEST([0.55, 0.60, 0.62, 0.65, 0.70, 0.75]) s,
       UNNEST([0.05, 0.10, 0.15, 0.20]) m
)
SELECT
  g.score_th,
  g.margin_th,
  COUNTIF(e.best_score >= g.score_th AND e.margin >= g.margin_th) AS would_fire,
  COUNT(*) AS total_evals
FROM grid g
CROSS JOIN `PROJECT_ID.wifi_delivery.v_match_evaluated` e
WHERE e.event_ts >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 30 DAY)
GROUP BY 1, 2
ORDER BY 1, 2;
*/

-- 2) 인접 세대 혼동 쌍: 1등과 2등이 계속 붙는 조합 찾기
--    여기 자주 나오는 세대 쌍은 같은 라인일 가능성이 높고,
--    서명을 다시 수집하거나 마진 기준을 세대별로 올려야 한다.
/*
SELECT
  best_member_id,
  runner_up_member_id,
  COUNT(*)          AS confusion_count,
  AVG(margin)       AS avg_margin,
  MIN(margin)       AS min_margin
FROM `PROJECT_ID.wifi_delivery.v_match_evaluated`
WHERE runner_up_member_id IS NOT NULL
  AND margin < 0.15
  AND best_score >= 0.55
GROUP BY 1, 2
HAVING confusion_count >= 5
ORDER BY confusion_count DESC;
*/

-- 3) 서명 노후화: 마지막 수집 이후 커버리지가 떨어지는 세대
--    공유기 교체·이사 신호. 재수집 대상 목록으로 쓴다.
/*
SELECT
  best_member_id,
  DATE(event_ts)        AS d,
  AVG(best_coverage)    AS avg_coverage,
  AVG(best_score)       AS avg_score,
  COUNT(*)              AS evals
FROM `PROJECT_ID.wifi_delivery.v_match_evaluated`
WHERE best_member_id IS NOT NULL
GROUP BY 1, 2
ORDER BY 1, 2;
*/
