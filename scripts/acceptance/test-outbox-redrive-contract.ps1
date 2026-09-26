#requires -Version 7.4
# Offline contract checks only: no Docker, database, service, or maintenance execution.
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'outbox-redrive-sql.ps1')
$checks = 0
function Assert-Contract([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw "Outbox redrive SQL contract failed: $Name" }
    $script:checks++
}
$request = [guid]'af807ec6-d0b3-463f-b109-b29bdf4dd200'
$untrustedText = "review'); DELETE FROM orders; --"
$sql = New-OutboxRedriveSql -EventId 9007199254740993 -ExpectedStatus 3 -ExpectedRedriveCount 2 `
    -Actor 'local-review' -Reason $untrustedText -RequestId $request
Assert-Contract ($sql.Contains('event_id = 9007199254740993')) 'long event id remains exact'
Assert-Contract (-not $sql.Contains($untrustedText)) 'operator text is not SQL syntax'
Assert-Contract ($sql.Contains([Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($untrustedText)))) 'operator text preserved as UTF-8 hex'
Assert-Contract ($sql.Contains('AND status = 3 AND redrive_count = 2')) 'explicit dead state and generation CAS'
Assert-Contract ($sql.Contains("event_type = 'PAYMENT_CONFIRMED'")) 'event type restricted'
Assert-Contract ($sql.Contains('AND o.status = 2 AND o.out_trade_no IS NOT NULL')) 'payment fact required'
Assert-Contract ($sql.Contains('AND o.pay_time IS NOT NULL')) 'payment timestamp required'
Assert-Contract ($sql.Contains("JSON_EXTRACT(e.payload, '$.orderId')")) 'payload and paid business key match'
Assert-Contract ($sql.Contains('FOR UPDATE;')) 'facts locked during maintenance'
Assert-Contract ($sql.Contains('lease_owner IS NULL AND lease_until IS NULL')) 'no active claim overridden'
Assert-Contract ($sql.Contains('SET @r05_changed = ROW_COUNT();')) 'audit gated on affected-row result'
Assert-Contract ($sql.Contains('previous_last_error, previous_next_retry_time, previous_update_time, previous_redrive_count')) 'prior failure facts audited'
Assert-Contract ($sql.Contains('redrive_count = redrive_count + 1')) 'stale operator intent invalidated'
Assert-Contract ($sql.IndexOf('START TRANSACTION;') -lt $sql.IndexOf('INSERT INTO fulfillment_order.order_outbox_redrive_audit')) 'audit inside transaction'
Assert-Contract ($sql.IndexOf('COMMIT;') -gt $sql.IndexOf('WHERE @r05_changed = 1;')) 'audit and requeue commit together'
$invalidRejected = $false
try {
    New-OutboxRedriveSql -EventId 1 -ExpectedStatus 2 -ExpectedRedriveCount 0 -Actor 'test' -Reason 'test' -RequestId $request | Out-Null
} catch { $invalidRejected = $true }
Assert-Contract $invalidRejected 'sent state cannot be requested'
[pscustomobject]@{ passed=$true; assertions=$checks; scope='offline SQL generation contracts only' } | ConvertTo-Json
