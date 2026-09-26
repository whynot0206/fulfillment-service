#requires -Version 7.4
[CmdletBinding()]
param([string]$ProjectName = 'fulfillment-acceptance', [switch]$RequireSettled)
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'state-invariants.ps1')
$context = Get-AcceptanceContext $ProjectName
$mysql = Get-AcceptanceContainer $context 'mysql'
if (-not $mysql) { throw 'Acceptance MySQL container not found.' }
# Read-only checks in the explicitly owned acceptance instance. No repair/reset SQL.
$sql = @'
SELECT JSON_OBJECT('kind','stock','skuId',s.sku_id,'available',s.stock,'locked',s.lock_stock,
 'activeLocks',COALESCE(SUM(IF(l.status=1,l.count,0)),0),
 'confirmed',COALESCE(SUM(IF(l.status=3,l.count,0)),0),
 'released',COALESCE(SUM(IF(l.status=2,l.count,0)),0))
FROM fulfillment_inventory.sku_stock s
LEFT JOIN fulfillment_inventory.sku_stock_lock l ON l.sku_id=s.sku_id
GROUP BY s.sku_id,s.stock,s.lock_stock ORDER BY s.sku_id;
SELECT JSON_OBJECT('kind','order','orderId',CAST(o.order_id AS CHAR),'status',o.status,
 'reservationStatus',o.reservation_status,'amount',o.total_amount,
 'outTradeNo',o.out_trade_no,'expireTime',o.expire_time,
 'locked',COALESCE((SELECT SUM(l.count) FROM fulfillment_inventory.sku_stock_lock l WHERE l.order_id=o.order_id AND l.status=1),0),
 'confirmed',COALESCE((SELECT SUM(l.count) FROM fulfillment_inventory.sku_stock_lock l WHERE l.order_id=o.order_id AND l.status=3),0),
 'released',COALESCE((SELECT SUM(l.count) FROM fulfillment_inventory.sku_stock_lock l WHERE l.order_id=o.order_id AND l.status=2),0),
 'itemCount',COALESCE((SELECT SUM(i.count) FROM fulfillment_order.order_item i WHERE i.order_id=o.order_id),0))
FROM fulfillment_order.`order` o ORDER BY o.order_id;
SELECT JSON_OBJECT('kind','outbox','orderId',biz_key,'type',event_type,'status',status,'retries',retry_count)
FROM fulfillment_order.order_outbox_event ORDER BY event_id;
'@
$rows = @( (Invoke-AcceptanceSql $mysql $sql) -split "`r?`n" | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json } )
$violations = @(Get-AcceptanceStateViolations -Rows $rows -RequireSettled:$RequireSettled)
$result = @{ recordedAt=[DateTime]::UtcNow.ToString('o'); project=$ProjectName; readOnly=$true;
    requireSettled=[bool]$RequireSettled; passed=($violations.Count -eq 0); violations=@($violations); rows=$rows }
Save-AcceptanceJson -Path (Join-Path $context.Runtime 'state-verification.json') -Value $result
$result | ConvertTo-Json -Depth 8
if ($violations.Count -gt 0) { throw 'Acceptance state assertions failed; data was not modified.' }
