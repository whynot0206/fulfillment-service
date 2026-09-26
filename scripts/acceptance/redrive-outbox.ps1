#requires -Version 7.4
[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$ProjectName = 'fulfillment-acceptance',
    [Parameter(Mandatory)][ValidateRange(1, [long]::MaxValue)][long]$EventId,
    [Parameter(Mandatory)][ValidateSet(3)][int]$ExpectedStatus,
    [Parameter(Mandatory)][ValidateRange(0, 2147483646)][int]$ExpectedRedriveCount,
    [Parameter(Mandatory)][ValidateLength(1, 128)][string]$Actor,
    [Parameter(Mandatory)][ValidateLength(1, 500)][string]$Reason,
    [guid]$RequestId = [guid]::NewGuid(),
    [switch]$Execute
)
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'outbox-redrive-sql.ps1')
# Validate and render before reaching Docker. The SQL text and operator notes are not printed.
$redriveSql = New-OutboxRedriveSql -EventId $EventId -ExpectedStatus $ExpectedStatus `
    -ExpectedRedriveCount $ExpectedRedriveCount -Actor $Actor -Reason $Reason -RequestId $RequestId
$context = Get-AcceptanceContext $ProjectName
$mysql = Get-AcceptanceContainer $context 'mysql'
if (-not $mysql) { throw 'Acceptance MySQL container not found.' }
$ready = Invoke-AcceptanceSql $mysql "SELECT COUNT(*) FROM acceptance_control.bootstrap WHERE id = 1 AND status = 'READY';"
if ($ready -ne '1') { throw 'Only a fully initialized owned acceptance instance can be maintained.' }

$previewTemplate = @'
SELECT JSON_OBJECT('eventId', CAST(e.event_id AS CHAR), 'status', e.status,
 'redriveCount', e.redrive_count, 'retryCount', e.retry_count,
 'eligible', IF(e.event_type = 'PAYMENT_CONFIRMED' AND e.status = 3
   AND e.redrive_count = {{GENERATION}} AND e.lease_owner IS NULL AND e.lease_until IS NULL
   AND EXISTS(SELECT 1 FROM fulfillment_order.`order` o
      WHERE e.biz_key = CAST(o.order_id AS CHAR) AND o.status = 2
        AND o.out_trade_no IS NOT NULL AND CHAR_LENGTH(o.out_trade_no) > 0 AND o.pay_time IS NOT NULL
        AND CASE WHEN JSON_VALID(e.payload)
             THEN JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$.orderId')) ELSE NULL END = CAST(o.order_id AS CHAR)), 1, 0))
 FROM fulfillment_order.order_outbox_event e WHERE e.event_id = {{EVENT_ID}};
'@
$previewSql = $previewTemplate.Replace('{{EVENT_ID}}', $EventId.ToString([Globalization.CultureInfo]::InvariantCulture)).
    Replace('{{GENERATION}}', $ExpectedRedriveCount.ToString())
$previewText = Invoke-AcceptanceSql $mysql $previewSql -Operation 'Read payment outbox maintenance target'
if ([string]::IsNullOrWhiteSpace($previewText)) { throw 'The requested event does not exist; nothing changed.' }
$preview = $previewText | ConvertFrom-Json
if ($preview.eligible -ne 1) {
    throw 'Event is not the expected unleased dead PAYMENT_CONFIRMED event with a matching paid order; nothing changed.'
}
if (-not $Execute -or -not $PSCmdlet.ShouldProcess("$ProjectName event $EventId", 'Requeue one verified dead payment event with audit')) {
    [pscustomobject]@{ previewOnly=$true; event=$preview; requestId=$RequestId.ToString('D');
        note='No write performed. Fix and verify the dependency first; use -Execute only for this exact event.' } | ConvertTo-Json -Depth 5
    return
}
try {
    $result = Invoke-AcceptanceSql $mysql $redriveSql -Operation 'Audited payment outbox redrive' | ConvertFrom-Json
} catch {
    throw "Redrive did not return a confirmed result. Inspect audit request $RequestId before retrying; no automatic retry was made."
}
if ($result.changed -ne 1) {
    throw 'Event changed after preview or payment facts no longer match; no redrive was applied.'
}
$result | ConvertTo-Json
# This confirms only requeue + audit, not inventory confirmation. Verify terminal state separately.
