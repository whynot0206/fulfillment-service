# Pure SQL construction for the local maintenance command and its offline contract test.
# No connection, command execution, or file writes occur when this file is dot-sourced.
function New-OutboxRedriveSql {
    param(
        [Parameter(Mandatory)][ValidateRange(1, [long]::MaxValue)][long]$EventId,
        [Parameter(Mandatory)][ValidateSet(3)][int]$ExpectedStatus,
        [Parameter(Mandatory)][ValidateRange(0, 2147483646)][int]$ExpectedRedriveCount,
        [Parameter(Mandatory)][ValidateLength(1, 128)][string]$Actor,
        [Parameter(Mandatory)][ValidateLength(1, 500)][string]$Reason,
        [Parameter(Mandatory)][guid]$RequestId
    )
    if ([string]::IsNullOrWhiteSpace($Actor) -or [string]::IsNullOrWhiteSpace($Reason)) {
        throw 'Actor and Reason must describe this specific maintenance decision.'
    }
    $actorHex = [Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($Actor))
    $reasonHex = [Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($Reason))
    # Values are typed numbers, a Guid, or UTF-8 hex; arbitrary operator text is never SQL syntax.
    $template = @'
SET SESSION innodb_lock_wait_timeout = 5;
SET @r05_previous_status = NULL, @r05_previous_retries = NULL, @r05_previous_error = NULL,
    @r05_previous_retry_time = NULL, @r05_previous_update_time = NULL,
    @r05_previous_generation = NULL, @r05_order_id = NULL, @r05_changed = 0;
START TRANSACTION;
SELECT e.status, e.retry_count, e.last_error, e.next_retry_time, e.update_time, e.redrive_count, o.order_id
  INTO @r05_previous_status, @r05_previous_retries, @r05_previous_error, @r05_previous_retry_time,
       @r05_previous_update_time, @r05_previous_generation, @r05_order_id
  FROM fulfillment_order.order_outbox_event e
  JOIN fulfillment_order.`order` o ON e.biz_key = CAST(o.order_id AS CHAR)
 WHERE e.event_id = {{EVENT_ID}} AND e.event_type = 'PAYMENT_CONFIRMED'
   AND e.status = {{EXPECTED_STATUS}} AND e.redrive_count = {{EXPECTED_GENERATION}}
   AND e.lease_owner IS NULL AND e.lease_until IS NULL
   AND o.status = 2 AND o.out_trade_no IS NOT NULL AND CHAR_LENGTH(o.out_trade_no) > 0
   AND o.pay_time IS NOT NULL
   AND CASE WHEN JSON_VALID(e.payload)
            THEN JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$.orderId')) ELSE NULL END = CAST(o.order_id AS CHAR)
 FOR UPDATE;
UPDATE fulfillment_order.order_outbox_event
   SET status = 0, retry_count = 0, next_retry_time = CURRENT_TIMESTAMP,
       lease_owner = NULL, lease_until = NULL, redrive_count = redrive_count + 1,
       update_time = CURRENT_TIMESTAMP
 WHERE event_id = {{EVENT_ID}} AND event_type = 'PAYMENT_CONFIRMED'
   AND status = {{EXPECTED_STATUS}} AND redrive_count = {{EXPECTED_GENERATION}}
   AND lease_owner IS NULL AND lease_until IS NULL AND @r05_order_id IS NOT NULL;
SET @r05_changed = ROW_COUNT();
INSERT INTO fulfillment_order.order_outbox_redrive_audit
    (request_id, event_id, order_id, actor, reason, previous_status, previous_retry_count,
     previous_last_error, previous_next_retry_time, previous_update_time, previous_redrive_count)
SELECT '{{REQUEST_ID}}', {{EVENT_ID}}, @r05_order_id,
       CONVERT(0x{{ACTOR_HEX}} USING utf8mb4), CONVERT(0x{{REASON_HEX}} USING utf8mb4),
       @r05_previous_status, @r05_previous_retries, @r05_previous_error, @r05_previous_retry_time,
       @r05_previous_update_time, @r05_previous_generation
 WHERE @r05_changed = 1;
COMMIT;
SELECT JSON_OBJECT('changed', @r05_changed, 'eventId', '{{EVENT_ID}}',
                   'requestId', '{{REQUEST_ID}}', 'previousRedriveCount', {{EXPECTED_GENERATION}});
'@
    $template.Replace('{{EVENT_ID}}', $EventId.ToString([Globalization.CultureInfo]::InvariantCulture)).
        Replace('{{EXPECTED_STATUS}}', $ExpectedStatus.ToString()).
        Replace('{{EXPECTED_GENERATION}}', $ExpectedRedriveCount.ToString()).
        Replace('{{REQUEST_ID}}', $RequestId.ToString('D')).
        Replace('{{ACTOR_HEX}}', $actorHex).
        Replace('{{REASON_HEX}}', $reasonHex)
}
