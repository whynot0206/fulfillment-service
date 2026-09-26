#requires -Version 7.4
<# Real isolated HTTP faults only. No direct business SQL writes or shortened retries.
   StartDeadLetter deliberately leaves Inventory confirmation rejected; FinishDeadLetter
   must be run afterwards. Do not change proxy 18882 or pay other orders during this window.
   Runtime state contains IDs and measurements only, never login tokens or credentials.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('StartDeadLetter','FinishDeadLetter','DeadLetter','ConfirmCrash','ReleaseFailure')][string]$Case,
    [string]$ProjectName = 'fulfillment-acceptance',
    [string]$JavaPath,
    [ValidateRange(60,2400)][int]$WaitSeconds = 1200,
    [string]$EvidencePath
)
. (Join-Path $PSScriptRoot 'common.ps1')
$context = Get-AcceptanceContext $ProjectName
$statePath = Join-Path $context.Runtime 'outbox-closure-dead-letter.json'
$checks = [Collections.Generic.List[object]]::new()
$observations = [Collections.Generic.List[object]]::new()
$fixtures = [Collections.Generic.List[object]]::new()
$script:token = ''
$state = $null
$passed = $false
$preserveFault = $false
$faultChanged = $false
$stage = 'preflight'
$orderStopped = $false

function Assert-Closure([string]$Name, [bool]$Condition) {
    $checks.Add(@{ name=$Name; passed=$Condition })
    if (-not $Condition) { throw "Outbox closure assertion failed: $Name" }
}
function Assert-OwnedListeners {
    $backend = Get-Content (Join-Path $context.Runtime 'backend-processes.json') -Raw | ConvertFrom-Json
    $proxies = Get-Content (Join-Path $context.Runtime 'fault-proxies.json') -Raw | ConvertFrom-Json
    if ($backend.project -ne $ProjectName -or $proxies.project -ne $ProjectName) {
        throw 'Selected project does not own the HTTP registries; no traffic sent.'
    }
    foreach ($pair in @(@('gateway',18080),@('order-service',18081),@('inventory-service',18082),
            @('payment-service',18083),@('commerce-service',18084))) {
        if (@($backend.processes | Where-Object { $_.service -eq $pair[0] -and $_.port -eq $pair[1] }).Count -ne 1) {
            throw 'Closure tests require the documented owned backend ports.'
        }
    }
    foreach ($port in @(18881,18882)) {
        if (@($proxies.processes | Where-Object port -eq $port).Count -ne 1) { throw 'Owned fault proxies are required.' }
    }
    foreach ($record in @($backend.processes) + @($proxies.processes)) {
        $owned = Get-AcceptanceOwnedProcess $record
        $listeners = @(Get-NetTCPConnection -LocalPort $record.port -State Listen -ErrorAction SilentlyContinue)
        if (-not $owned -or $listeners.Count -eq 0 -or @($listeners | Where-Object {
                    $_.OwningProcess -ne $owned.Id -or $_.LocalAddress -ne '127.0.0.1' }).Count -gt 0) {
            throw 'Every test listener must be loopback and owned by the selected acceptance project.'
        }
    }
    foreach ($record in @($backend.processes | Where-Object service -in @('order-service','commerce-service'))) {
        if (-not $record.PSObject.Properties['usesFaultProxy'] -or -not $record.usesFaultProxy) {
            throw 'Restart owned Order and Commerce with -UseFaultProxies before this test.'
        }
    }
}
function Read-Fact([string]$Sql) {
    if ($Sql -notmatch '^\s*SELECT\b') { throw 'Evidence queries must be read-only SELECT statements.' }
    Invoke-AcceptanceSql $mysql $Sql
}
function Proxy-State {
    Invoke-RestMethod 'http://127.0.0.1:18882/__fault__/state' -NoProxy -TimeoutSec 3 -Headers @{'X-Fault-Token'=$internal}
}
function Set-Fault([string]$Mode, [string]$Path='', [int]$Remaining=0) {
    $script:faultChanged = $true
    Invoke-RestMethod 'http://127.0.0.1:18882/__fault__/state' -Method Post -NoProxy -TimeoutSec 3 `
        -Headers @{'X-Fault-Token'=$internal} -ContentType 'application/json' `
        -Body (@{mode=$Mode;path=$Path;remaining=$Remaining} | ConvertTo-Json -Compress)
}
function Api([string]$Method, [string]$Path, $Body=$null, [string]$Key='') {
    $headers = @{Authorization="Bearer $script:token"}
    if ($Key) { $headers['Idempotency-Key'] = $Key }
    $arguments = @{Uri="http://127.0.0.1:18080$Path";Method=$Method;Headers=$headers;
        SkipHttpErrorCheck=$true;NoProxy=$true;TimeoutSec=15}
    if ($null -ne $Body) { $arguments.Body=ConvertTo-Json $Body -Depth 8 -Compress; $arguments.ContentType='application/json' }
    $response = Invoke-WebRequest @arguments
    @{status=[int]$response.StatusCode;body=($response.Content | ConvertFrom-Json -AsHashtable)}
}
function Stock-Fact([long]$SkuId) {
    $text = Read-Fact "SELECT JSON_OBJECT('stock',stock,'locked',lock_stock) FROM fulfillment_inventory.sku_stock WHERE sku_id=$SkuId;"
    if (-not $text) { throw 'Fixture SKU not found.' }
    $text | ConvertFrom-Json -AsHashtable
}
function Event-Fact([string]$OrderId) {
    if ($OrderId -notmatch '^[1-9][0-9]{0,18}$') { throw 'Invalid recorded fixture order ID.' }
    $text = Read-Fact "SELECT JSON_OBJECT('eventId',CAST(event_id AS CHAR),'status',status,'retryCount',retry_count,'redriveCount',redrive_count,'delaySeconds',TIMESTAMPDIFF(SECOND,update_time,next_retry_time),'updatedEpoch',UNIX_TIMESTAMP(update_time),'leaseOwner',lease_owner,'leaseUntilEpoch',UNIX_TIMESTAMP(lease_until),'databaseEpoch',UNIX_TIMESTAMP(CURRENT_TIMESTAMP(6))) FROM fulfillment_order.order_outbox_event WHERE event_type='PAYMENT_CONFIRMED' AND biz_key='$OrderId';"
    if (-not $text) { return $null }
    $text | ConvertFrom-Json -AsHashtable
}
function Save-State {
    $state.checks = $checks.ToArray()
    $state.observations = $observations.ToArray()
    Save-AcceptanceJson $statePath $state
}
function Wait-Expected([string]$Name, [string]$Sql, [string]$Expected, [int]$Seconds=45) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        if ((Read-Fact $Sql) -eq $Expected) { Assert-Closure $Name $true; return }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    Assert-Closure $Name $false
}
function Same-Stock($Left, $Right) {
    return $Left.stock -eq $Right.stock -and $Left.locked -eq $Right.locked
}
function New-Fixture([long]$SkuId, [string]$Label) {
    $runId = [guid]::NewGuid().ToString('N')
    $auth = Api POST '/api/auth/register' @{username=('closure_' + $runId.Substring(0,20));
        password=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))}
    Assert-Closure "$Label.user-created" ($auth.status -eq 201)
    $script:token = $auth.body.token
    $before = Stock-Fact $SkuId
    $cart = Api POST '/api/cart/items' @{skuId=$SkuId;quantity=1}
    Assert-Closure "$Label.cart-ready" ($cart.status -eq 200)
    $checkout = Api POST '/api/checkout' @{expectedAmount=[decimal]$cart.body.selectedAmount} "$runId-$Label"
    Assert-Closure "$Label.order-reserved" ($checkout.status -eq 200 -and $checkout.body.state -eq 'RESERVED' -and $checkout.body.orderId -is [string])
    $id = [string]$checkout.body.orderId
    Assert-Closure "$Label.real-owned-order" ((Read-Fact "SELECT COUNT(*) FROM fulfillment_order.``order`` WHERE order_id=$id AND user_id=$($auth.body.userId) AND status=1 AND reservation_status=1;") -eq '1')
    $fixture = @{runId=$runId;orderId=$id;userId=[long]$auth.body.userId;skuId=$SkuId;before=$before;reserved=(Stock-Fact $SkuId)}
    $fixtures.Add($fixture)
    Assert-Closure "$Label.single-unit-stock-reserved" ($fixture.reserved.stock -eq $before.stock - 1 -and $fixture.reserved.locked -eq $before.locked + 1)
    return $fixture
}
function Start-DeadLetter {
    if (Test-Path -LiteralPath $statePath) {
        $prior = Get-Content $statePath -Raw | ConvertFrom-Json -AsHashtable
        if ($prior.phase -ne 'Completed') { throw 'An unfinished dead-letter fixture exists; finish or inspect it instead of starting another.' }
    }
    $proxy = Proxy-State
    Assert-Closure 'F07.proxy-starts-idle' ($proxy.mode -eq 'pass' -and $proxy.held -eq 0)
    Assert-Closure 'F07.no-unrelated-pending-confirmations' ((Read-Fact "SELECT COUNT(*) FROM fulfillment_order.order_outbox_event WHERE event_type='PAYMENT_CONFIRMED' AND status IN (0,1);") -eq '0')
    $fixture = New-Fixture 1005 'F07'
    $script:state = @{version=1;project=$ProjectName;mysqlContainer=$mysql;phase='Armed';
        startedAtUtc=[DateTime]::UtcNow.ToString('o');fixture=$fixture;eventId=$null;checks=@();observations=@()}
    Save-State
    $null = Set-Fault reject '/internal/inventory/confirm' 100
    $payment = Api POST "/api/payments/orders/$($fixture.orderId)/mock-success"
    Assert-Closure 'F07.payment-committed' ($payment.status -eq 200)
    $script:preserveFault = $true
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $event = Event-Fact $fixture.orderId
        if ($event -and $event.retryCount -gt 0) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    Assert-Closure 'F07.first-default-retry-observed' ($event -and $event.status -eq 0 -and $event.retryCount -eq 1 -and $event.delaySeconds -eq 2)
    Assert-Closure 'F07.failed-confirmation-keeps-inventory-locked' ((Read-Fact "SELECT CONCAT(COUNT(*),'|',SUM(count),'|',MIN(status)) FROM fulfillment_inventory.sku_stock_lock WHERE order_id=$($fixture.orderId);") -eq '1|1|1')
    $observations.Add($event)
    $state.eventId = $event.eventId
    $state.phase = 'WaitingForDeadLetter'
    $state.proxyHitsAtStart = $proxy.hits
    Save-State
    Write-Output "F07 started; default 10-failure schedule is running. State: $statePath"
    Write-Output 'Keep proxy 18882 unchanged and do not pay other orders. Run -Case FinishDeadLetter to observe and redrive.'
}
function Expect-RedriveRejected([string]$Label, [long]$EventId, [int]$Generation, [guid]$RequestId) {
    $rejected = $false
    try {
        $null = & (Join-Path $PSScriptRoot 'redrive-outbox.ps1') -ProjectName $ProjectName `
            -EventId $EventId -ExpectedStatus 3 -ExpectedRedriveCount $Generation `
            -Actor 'acceptance-outbox-closure' -Reason $Label -RequestId $RequestId -Execute -Confirm:$false
    } catch {
        # A connection failure is not proof that the maintenance guard rejected the request.
        if ($_.Exception.Message -notlike 'Event is not the expected unleased dead PAYMENT_CONFIRMED event*') { throw }
        $rejected = $true
    }
    Assert-Closure $Label $rejected
}
function Finish-DeadLetter {
    $candidate = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json -AsHashtable
    if ($candidate.version -ne 1 -or $candidate.project -ne $ProjectName -or $candidate.mysqlContainer -ne $mysql -or
        $candidate.phase -ne 'WaitingForDeadLetter' -or [string]$candidate.eventId -notmatch '^[1-9][0-9]{0,18}$') {
        throw 'Expected this deployment''s unfinished dead-letter fixture; nothing changed.'
    }
    $script:state = $candidate
    # Separate Start/Finish invocations can miss early samples; preserve exactly what was observed.
    if ($checks.Count -eq 0) { foreach ($check in $state.checks) { $checks.Add($check) } }
    if ($observations.Count -eq 0) { foreach ($sample in $state.observations) { $observations.Add($sample) } }
    $fixture = $state.fixture
    $fixtures.Add($fixture)
    $id = [string]$fixture.orderId
    $eventId = [long]$state.eventId
    Assert-Closure 'F07.fixture-still-identifies-the-paid-order' ((Read-Fact "SELECT COUNT(*) FROM fulfillment_order.``order`` WHERE order_id=$id AND user_id=$($fixture.userId) AND status=2;") -eq '1')
    $script:preserveFault = $true
    $deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
    $nextProgress = [DateTime]::UtcNow
    $lastCount = if ($observations.Count) { [int]$observations[$observations.Count-1].retryCount } else { 0 }
    do {
        $proxy = Proxy-State
        if (-not ($proxy.mode -eq 'reject' -and $proxy.path -eq '/internal/inventory/confirm' -and $proxy.remaining -gt 0 -and $proxy.held -eq 0)) {
            Assert-Closure 'F07.reject-window-has-not-been-replaced' $false
        }
        $event = Event-Fact $id
        if (-not ($event -and [string]$event.eventId -ceq [string]$state.eventId -and $event.redriveCount -eq 0 -and $event.retryCount -le 10 -and $event.status -in @(0,1,3))) {
            Assert-Closure 'F07.event-remains-in-original-generation' $false
        }
        if ($event.status -in @(0,3) -and $event.retryCount -gt $lastCount) {
            $expectedDelay = [int][Math]::Pow(2, [Math]::Min([int]$event.retryCount,8))
            Assert-Closure "F07.retry-$($event.retryCount)-uses-default-backoff" ($event.delaySeconds -eq $expectedDelay)
            Assert-Closure "F07.retry-$($event.retryCount)-released-lease" ($null -eq $event.leaseOwner -and $null -eq $event.leaseUntilEpoch)
            if ($observations.Count -gt 0 -and $event.retryCount -eq $lastCount + 1) {
                $previous = $observations[$observations.Count-1]
                Assert-Closure "F07.retry-$($event.retryCount)-waited-previous-delay" (($event.updatedEpoch - $previous.updatedEpoch) -ge $previous.delaySeconds)
            }
            $observations.Add($event)
            $lastCount = [int]$event.retryCount
            Save-State
        }
        if ([DateTime]::UtcNow -ge $nextProgress) {
            Write-Output "F07 waiting: completed failures=$($event.retryCount), event status=$($event.status); default retry timing unchanged."
            $nextProgress = [DateTime]::UtcNow.AddSeconds(20)
        }
        if ($event.status -eq 3) { break }
        Start-Sleep -Milliseconds 700
    } while ([DateTime]::UtcNow -lt $deadline)
    Assert-Closure 'F07.reject-window-has-not-been-replaced' $true
    Assert-Closure 'F07.event-remains-in-original-generation' $true
    Assert-Closure 'F07.exactly-ten-real-failures-reach-dead-letter' ($event.status -eq 3 -and $event.retryCount -eq 10)
    Assert-Closure 'F07.default-retries-consumed-real-wall-time' (($event.updatedEpoch - $observations[0].updatedEpoch) -ge 766)
    Assert-Closure 'F07.exactly-ten-confirmation-fault-hits' (($proxy.hits - $state.proxyHitsAtStart) -eq 10)
    Assert-Closure 'F07.dead-letter-keeps-original-stock-lock' (Same-Stock (Stock-Fact $fixture.skuId) $fixture.reserved)
    Assert-Closure 'F07.dead-letter-has-no-prior-redrive-audit' ((Read-Fact "SELECT COUNT(*) FROM fulfillment_order.order_outbox_redrive_audit WHERE event_id=$eventId;") -eq '0')
    Expect-RedriveRejected 'F07.wrong-dead-letter-generation-is-rejected' $eventId 1 ([guid]::NewGuid())
    Assert-Closure 'F07.wrong-generation-does-not-change-dead-letter' ((Read-Fact "SELECT CONCAT(status,'|',retry_count,'|',redrive_count) FROM fulfillment_order.order_outbox_event WHERE event_id=$eventId;") -eq '3|10|0')
    $null = Set-Fault pass
    $script:preserveFault = $false
    $health = Invoke-RestMethod 'http://127.0.0.1:18082/actuator/health' -NoProxy -TimeoutSec 3
    Assert-Closure 'F07.dependency-restored-before-maintenance' ($health.status -eq 'UP' -and (Proxy-State).mode -eq 'pass')
    $requestId = [guid]::NewGuid()
    $state.redriveRequestId = $requestId.ToString('D')
    $state.phase = 'Redriving'
    Save-State
    $redriveText = & (Join-Path $PSScriptRoot 'redrive-outbox.ps1') -ProjectName $ProjectName `
        -EventId $eventId -ExpectedStatus 3 -ExpectedRedriveCount 0 -Actor 'acceptance-outbox-closure' `
        -Reason 'F07 dependency restored after real default retry exhaustion' -RequestId $requestId -Execute -Confirm:$false
    $redrive = $redriveText | ConvertFrom-Json
    Assert-Closure 'F07.audited-redrive-applied-once' ($redrive.changed -eq 1)
    Wait-Expected 'F07.redriven-event-becomes-sent' "SELECT CONCAT(status,'|',retry_count,'|',redrive_count,'|',IF(lease_owner IS NULL AND lease_until IS NULL,1,0)) FROM fulfillment_order.order_outbox_event WHERE event_id=$eventId;" '2|0|1|1'
    Assert-Closure 'F07.audit-retains-original-failure-generation' ((Read-Fact "SELECT COUNT(*) FROM fulfillment_order.order_outbox_redrive_audit WHERE event_id=$eventId AND order_id=$id AND request_id='$requestId' AND previous_status=3 AND previous_retry_count=10 AND previous_redrive_count=0 AND actor='acceptance-outbox-closure' AND previous_last_error IS NOT NULL;") -eq '1')
    Assert-Closure 'F07.one-reservation-confirmed' ((Read-Fact "SELECT CONCAT(COUNT(*),'|',SUM(count),'|',MIN(status)) FROM fulfillment_inventory.sku_stock_lock WHERE order_id=$id;") -eq '1|1|3')
    $after = Stock-Fact $fixture.skuId
    Assert-Closure 'F07.confirmation-consumes-no-second-available-unit' ($after.stock -eq $fixture.reserved.stock -and $after.locked -eq $fixture.before.locked)
    Expect-RedriveRejected 'F07.duplicate-maintenance-request-is-rejected' $eventId 0 $requestId
    Expect-RedriveRejected 'F07.sent-event-cannot-be-redriven-with-new-generation' $eventId 1 ([guid]::NewGuid())
    Assert-Closure 'F07.rejected-repeats-do-not-add-audit-or-change-generation' ((Read-Fact "SELECT CONCAT((SELECT COUNT(*) FROM fulfillment_order.order_outbox_redrive_audit WHERE event_id=$eventId),'|',status,'|',redrive_count) FROM fulfillment_order.order_outbox_event WHERE event_id=$eventId;") -eq '1|2|1')
    $state.phase = 'Completed'
    $state.completedAtUtc = [DateTime]::UtcNow.ToString('o')
    $state.observedRetryCounts = @($observations | ForEach-Object { $_.retryCount })
    Save-State
}

function Restart-OwnedBackends {
    & (Join-Path $PSScriptRoot 'start-backend.ps1') -ProjectName $ProjectName -JavaPath $JavaPath -OrderTimeoutSeconds 180 -UseFaultProxies
    $script:orderStopped = $false
}
function Confirm-Crash {
    if ([string]::IsNullOrWhiteSpace($JavaPath)) { throw '-JavaPath is required for the owned Order restart.' }
    $proxy = Proxy-State
    Assert-Closure 'F06.proxy-starts-idle' ($proxy.mode -eq 'pass' -and $proxy.held -eq 0)
    Assert-Closure 'F06.no-unrelated-pending-confirmations' ((Read-Fact "SELECT COUNT(*) FROM fulfillment_order.order_outbox_event WHERE event_type='PAYMENT_CONFIRMED' AND status IN (0,1);") -eq '0')
    $fixture = New-Fixture 1004 'F06'
    $id = [string]$fixture.orderId
    $null = Set-Fault hold-after '/internal/inventory/confirm' 1
    $payment = Api POST "/api/payments/orders/$id/mock-success"
    Assert-Closure 'F06.payment-committed' ($payment.status -eq 200)
    $deadline = [DateTime]::UtcNow.AddSeconds(3)
    do {
        $held = Proxy-State
        if ($held.held -eq 1) { break }
        Start-Sleep -Milliseconds 20
    } while ([DateTime]::UtcNow -lt $deadline)
    Assert-Closure 'F06.inventory-confirmation-committed-response-held' ($held.held -eq 1 -and $held.lastFault.mode -eq 'hold-after' -and $held.lastFault.completed -and $held.lastFault.businessStatus -eq 'CONFIRMED')
    & (Join-Path $PSScriptRoot 'stop-backend.ps1') -ProjectName $ProjectName -Services order-service
    $script:orderStopped = $true
    $event = Event-Fact $id
    Assert-Closure 'F06.crash-leaves-inflight-event-before-publisher-writeback' ($event.status -eq 1 -and $event.retryCount -eq 0 -and $event.leaseOwner -and $event.leaseUntilEpoch -gt $event.databaseEpoch)
    Assert-Closure 'F06.claim-used-default-sixty-second-lease' (($event.leaseUntilEpoch - $event.updatedEpoch) -ge 60 -and ($event.leaseUntilEpoch - $event.updatedEpoch) -lt 61)
    Assert-Closure 'F06.inventory-is-already-confirmed' ((Read-Fact "SELECT CONCAT(COUNT(*),'|',SUM(count),'|',MIN(status)) FROM fulfillment_inventory.sku_stock_lock WHERE order_id=$id;") -eq '1|1|3')
    $confirmed = Stock-Fact $fixture.skuId
    Assert-Closure 'F06.first-confirmation-consumed-only-reserved-unit' ($confirmed.stock -eq $fixture.reserved.stock -and $confirmed.locked -eq $fixture.before.locked)
    $observations.Add(@{stage='F06-before-lease-expiry';event=$event;stock=$confirmed})
    Wait-Expected 'F06.actual-sixty-second-lease-expires-with-order-stopped' "SELECT IF(lease_until <= CURRENT_TIMESTAMP(6),1,0) FROM fulfillment_order.order_outbox_event WHERE event_id=$($event.eventId) AND status=1;" '1' 70
    $stillHeld = Proxy-State
    Assert-Closure 'F06.original-response-remains-held-through-lease-expiry' ($stillHeld.held -eq 1 -and $stillHeld.lastFault.sequence -eq $held.lastFault.sequence)
    $null = Set-Fault pass
    $beforeRetry = Proxy-State
    Restart-OwnedBackends
    Wait-Expected 'F06.expired-claim-recovers-and-is-sent' "SELECT CONCAT(status,'|',retry_count,'|',IF(lease_owner IS NULL AND lease_until IS NULL,1,0)) FROM fulfillment_order.order_outbox_event WHERE event_id=$($event.eventId);" '2|0|1'
    Assert-Closure 'F06.confirmation-was-forwarded-again' ((Proxy-State).forwarded -gt $beforeRetry.forwarded)
    Assert-Closure 'F06.duplicate-confirmation-does-not-deduct-again' (Same-Stock (Stock-Fact $fixture.skuId) $confirmed)
    Assert-Closure 'F06.original-order-and-single-reservation-stay-confirmed' ((Read-Fact "SELECT CONCAT((SELECT status FROM fulfillment_order.``order`` WHERE order_id=$id),'|',COUNT(*),'|',SUM(count),'|',MIN(status)) FROM fulfillment_inventory.sku_stock_lock WHERE order_id=$id;") -eq '2|1|1|3')
    $observations.Add(@{stage='F06-after-restart';event=(Event-Fact $id);stock=(Stock-Fact $fixture.skuId)})
}
function Release-Failure {
    $proxy = Proxy-State
    Assert-Closure 'F04.proxy-starts-idle' ($proxy.mode -eq 'pass' -and $proxy.held -eq 0)
    $fixture = New-Fixture 1003 'F04'
    $id = [string]$fixture.orderId
    $null = Set-Fault reject '/internal/inventory/release' 100
    $cancel = Api POST "/api/orders/$id/cancel"
    Assert-Closure 'F04.cancel-persists-despite-release-outage' ($cancel.status -eq 200 -and $cancel.body.state -eq 'CANCELED')
    Wait-Expected 'F04.cancellation-remains-pending-compensation' "SELECT CONCAT(status,'|',reservation_status) FROM fulfillment_order.``order`` WHERE order_id=$id;" '3|2'
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $failed = Proxy-State
        if ($failed.hits - $proxy.hits -ge 2) { break }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    Assert-Closure 'F04.background-release-retry-also-failed' ($failed.hits - $proxy.hits -ge 2 -and $failed.mode -eq 'reject' -and $failed.path -eq '/internal/inventory/release')
    Assert-Closure 'F04.failure-does-not-pretend-stock-is-released' (Same-Stock (Stock-Fact $fixture.skuId) $fixture.reserved)
    Assert-Closure 'F04.original-reservation-remains-locked' ((Read-Fact "SELECT CONCAT(COUNT(*),'|',SUM(count),'|',MIN(status)) FROM fulfillment_inventory.sku_stock_lock WHERE order_id=$id;") -eq '1|1|1')
    $null = Set-Fault pass
    Wait-Expected 'F04.compensation-converges-after-dependency-restored' "SELECT CONCAT(status,'|',reservation_status) FROM fulfillment_order.``order`` WHERE order_id=$id;" '3|3'
    Assert-Closure 'F04.stock-restored-exactly-once' (Same-Stock (Stock-Fact $fixture.skuId) $fixture.before)
    Assert-Closure 'F04.reservation-released-and-cancel-fence-retained' ((Read-Fact "SELECT CONCAT((SELECT status FROM fulfillment_inventory.inventory_reservation_fence WHERE order_id=$id),'|',COUNT(*),'|',SUM(count),'|',MIN(status)) FROM fulfillment_inventory.sku_stock_lock WHERE order_id=$id;") -eq '2|1|1|2')
    $again = Api POST "/api/orders/$id/cancel"
    Assert-Closure 'F04.repeated-cancel-keeps-terminal-state' ($again.status -eq 200 -and $again.body.state -eq 'CANCELED' -and (Same-Stock (Stock-Fact $fixture.skuId) $fixture.before))
}

Assert-OwnedListeners
$mysql = Get-AcceptanceContainer $context 'mysql'
$infra = Get-Content (Join-Path $context.Runtime 'infrastructure.json') -Raw | ConvertFrom-Json
if ($infra.project -ne $ProjectName -or $mysql -ne $infra.mysqlContainer) { throw 'Selected MySQL container identity does not match owned infrastructure.' }
$internal = Get-AcceptanceSecret 'ACCEPTANCE_INTERNAL_SERVICE_TOKEN'
if ((Read-Fact "SELECT COUNT(*) FROM acceptance_control.bootstrap WHERE id=1 AND status='READY';") -ne '1') { throw 'Owned database is not initialized.' }
$runLock = [IO.File]::Open((Join-Path $context.Runtime 'outbox-closure.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
try {
    $stage = $Case
    if ($Case -in @('ConfirmCrash','ReleaseFailure') -and (Test-Path -LiteralPath $statePath)) {
        $unfinished = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json -AsHashtable
        if ($unfinished.phase -ne 'Completed') { throw 'Finish or inspect the existing F07 fixture before changing Inventory faults.' }
    }
    switch ($Case) {
        'StartDeadLetter' { Start-DeadLetter; $passed=$true }
        'FinishDeadLetter' { Finish-DeadLetter; $passed=$true }
        'DeadLetter' { Start-DeadLetter; Finish-DeadLetter; $passed=$true }
        'ConfirmCrash' { Confirm-Crash; $passed=$true }
        'ReleaseFailure' { Release-Failure; $passed=$true }
    }
} finally {
    if ($faultChanged -and -not $preserveFault) {
        try { $null = Set-Fault pass } catch { Write-Warning 'Could not restore owned Inventory proxy; inspect its recorded process.' }
    }
    if ($orderStopped) {
        try { Restart-OwnedBackends } catch { Write-Warning 'Owned Order remains stopped; restart it explicitly with the recorded acceptance configuration.' }
    }
    if ($preserveFault) { Write-Warning 'Inventory confirmation fault remains active intentionally; finish or inspect this recorded F07 fixture before other payment tests.' }
    if ($state) { Save-State }
    $script:token = ''
    $result = @{case=$Case;stage=$stage;passed=$passed;recordedAtUtc=[DateTime]::UtcNow.ToString('o');
        checks=$checks.ToArray();observations=$observations.ToArray();fixtures=$fixtures.ToArray();
        note='Single-instance real HTTP faults; assertions do not establish multi-instance or production reliability.'}
    if ($EvidencePath) { Save-AcceptanceJson $EvidencePath $result }
    $runLock.Dispose()
    Write-Output "Outbox closure $Case checks=$($checks.Count), passed=$passed."
}
