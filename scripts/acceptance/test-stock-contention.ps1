#requires -Version 7.4
<# C01/N06/N07: real Commerce HTTP requests and read-only SQL evidence, not a mock/load test.
   Requires the owned five-service deployment with Order using the owned 18882 fault proxy.
   Run serially with other fault tests. Uses 31 fresh users; never adjusts stock through SQL.
   Creates a 90-unit holder only AFTER user/cart preparation, races 30 users for 10 units,
   then tests a two-SKU rollback by exhausting stock after Commerce's precheck.
   Finally cancels this run's orders through their owners' APIs; records are retained.
#>
[CmdletBinding()]
param([string]$ProjectName = 'fulfillment-acceptance', [string]$EvidencePath)
. (Join-Path $PSScriptRoot 'common.ps1')
$context = Get-AcceptanceContext $ProjectName
$mysql = Get-AcceptanceContainer $context 'mysql'
if (-not $mysql) { throw 'Owned acceptance MySQL is required.' }
$infra = Get-Content (Join-Path $context.Runtime 'infrastructure.json') -Raw | ConvertFrom-Json
$database = Get-Content (Join-Path $context.Runtime 'database.json') -Raw | ConvertFrom-Json
if ($infra.project -ne $ProjectName -or $database.project -ne $ProjectName -or $database.status -ne 'READY' -or
        $mysql -ne $infra.mysqlContainer -or $mysql -ne $database.mysqlContainer -or
        (Get-AcceptanceContainer $context 'redis') -ne $infra.redisContainer) {
    throw 'Dependency identity or initialized database metadata does not match this deployment.'
}
if ((Invoke-AcceptanceSql $mysql "SELECT COUNT(*) FROM acceptance_control.bootstrap WHERE id=1 AND status='READY';") -ne '1') {
    throw 'Owned database is not initialized; no fixture was created.'
}
$internal = Get-AcceptanceSecret 'ACCEPTANCE_INTERNAL_SERVICE_TOKEN'
$backend = Get-Content (Join-Path $context.Runtime 'backend-processes.json') -Raw | ConvertFrom-Json
$proxies = Get-Content (Join-Path $context.Runtime 'fault-proxies.json') -Raw | ConvertFrom-Json
foreach ($registry in @($backend, $proxies)) {
    if ($registry.project -ne $ProjectName) { throw 'Acceptance process ownership mismatch.' }
    foreach ($record in $registry.processes) {
        $owned = Get-AcceptanceOwnedProcess $record
        $listeners = @(Get-NetTCPConnection -LocalPort $record.port -State Listen -ErrorAction SilentlyContinue)
        if (-not $owned -or $listeners.Count -eq 0 -or @($listeners | Where-Object {
                $_.OwningProcess -ne $owned.Id -or $_.LocalAddress -ne '127.0.0.1'
            }).Count -gt 0) { throw 'Test listeners must be owned and loopback-only.' }
    }
}
$orderProcess = @($backend.processes | Where-Object { $_.service -eq 'order-service' -and $_.port -eq 18081 })
if ($orderProcess.Count -ne 1 -or -not $orderProcess[0].PSObject.Properties['usesFaultProxy'] -or
        -not $orderProcess[0].usesFaultProxy -or
        @($backend.processes | Where-Object { $_.service -eq 'gateway' -and $_.port -eq 18080 }).Count -ne 1 -or
        @($proxies.processes | Where-Object port -eq 18882).Count -ne 1 -or
        @($proxies.processes | Where-Object port -eq 18881).Count -ne 1) {
    throw 'Use the documented ports and restart owned Order with -UseFaultProxies first.'
}
$run = [guid]::NewGuid().ToString('N')
$started = [DateTime]::UtcNow
$checks = [Collections.Generic.List[object]]::new()
$actors = [Collections.Generic.List[object]]::new()
$intents = [Collections.Generic.List[object]]::new()
$allPending = [Collections.Generic.List[object]]::new()
$raceEvidence = [Collections.Generic.List[object]]::new()
$rollbackEvidence = [Collections.Generic.List[object]]::new()
$cleanupEvidence = [Collections.Generic.List[object]]::new()
$baseline = $null
$finalStock = $null
$counts = @{success=0;businessRejected=0;unknownOrInfrastructure=0}
$script:stage = 'ownership-preflight'
$passed = $false
$cleaned = $false
$faultOwned = $false
$failureStage = $null
$http = $null
$scenarioLock = [IO.File]::Open((Join-Path $context.Runtime 'stock-contention.lock'), 'OpenOrCreate', 'ReadWrite', 'None')

function Check([string]$Name, [bool]$Condition) {
    $checks.Add(@{name=$Name;passed=$Condition})
    if (-not $Condition) { throw "Stock acceptance assertion failed: $Name" }
}
function Api([string]$Method, [string]$Path, $Actor=$null, $Body=$null, [string]$Key='') {
    $headers = @{Accept='application/json'}
    if ($null -ne $Actor) { $headers.Authorization = "Bearer $($Actor.token)" }
    if ($Key) { $headers['Idempotency-Key']=$Key }
    $arguments = @{Uri="http://127.0.0.1:18080$Path";Method=$Method;Headers=$headers;
        SkipHttpErrorCheck=$true;NoProxy=$true;TimeoutSec=15;MaximumRedirection=0;Verbose=$false;Debug=$false}
    if ($null -ne $Body) { $arguments.Body=ConvertTo-Json $Body -Depth 6 -Compress; $arguments.ContentType='application/json' }
    try {
        $response = Invoke-WebRequest @arguments
        $bodyResult = if ([string]::IsNullOrWhiteSpace($response.Content)) { @{} } else {
            $response.Content | ConvertFrom-Json -AsHashtable
        }
        return @{status=[int]$response.StatusCode;body=$bodyResult}
    } catch { throw 'HTTP transport/JSON failed; credential-bearing details omitted.' }
}
function ProxyState([int]$Port=18882) {
    Invoke-RestMethod "http://127.0.0.1:$Port/__fault__/state" -NoProxy -TimeoutSec 2 `
        -Headers @{'X-Fault-Token'=$internal} -Verbose:$false -Debug:$false
}
function SetProxy([string]$Mode) {
    $spec = if ($Mode -eq 'pass') { @{mode='pass';path='';remaining=0} } else {
        @{mode='hold-before';path='/internal/inventory/reserve';remaining=1}
    }
    $null = Invoke-RestMethod 'http://127.0.0.1:18882/__fault__/state' -Method POST -NoProxy -TimeoutSec 2 `
        -Headers @{'X-Fault-Token'=$internal} -ContentType 'application/json' `
        -Body ($spec | ConvertTo-Json -Compress) -Verbose:$false -Debug:$false
}
function Stock {
    $raw = Invoke-AcceptanceSql $mysql @'
SELECT JSON_OBJECT('skuId',s.sku_id,'stock',s.stock,'locked',s.lock_stock,
 'activeLocks',COALESCE(SUM(IF(l.status=1,l.count,0)),0))
FROM fulfillment_inventory.sku_stock s
LEFT JOIN fulfillment_inventory.sku_stock_lock l ON l.sku_id=s.sku_id
WHERE s.sku_id IN (1001,1002) GROUP BY s.sku_id,s.stock,s.lock_stock ORDER BY s.sku_id;
'@
    @($raw -split "`r?`n" | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json -AsHashtable })
}
function IntentFact($Intent) {
    # User IDs are server-generated integers; keys consist exclusively of this script's Guid and labels.
    $uid = [long]$Intent.actor.userId
    $key = [string]$Intent.key
    if ($key -notmatch '^[a-f0-9]{32}-(holder|race-[0-9]+|multi-[0-9]+|drain-[0-9]+)$') { throw 'Unexpected synthetic key.' }
    $sql = @'
SELECT JSON_OBJECT('orderId',CAST(c.order_id AS CHAR),'checkoutStatus',c.status,
 'orderStatus',o.status,'reservationStatus',o.reservation_status,
 'activeLocks',(SELECT COUNT(*) FROM fulfillment_inventory.sku_stock_lock l WHERE l.order_id=c.order_id AND l.status=1),
 'confirmedLocks',(SELECT COUNT(*) FROM fulfillment_inventory.sku_stock_lock l WHERE l.order_id=c.order_id AND l.status=3),
 'allLocks',(SELECT COUNT(*) FROM fulfillment_inventory.sku_stock_lock l WHERE l.order_id=c.order_id))
FROM fulfillment_commerce.checkout_request c
LEFT JOIN fulfillment_order.`order` o ON o.order_id=c.order_id
WHERE c.user_id={{USER}} AND c.idempotency_key='{{KEY}}';
'@
    $raw = Invoke-AcceptanceSql $mysql ($sql.Replace('{{USER}}', $uid.ToString()).Replace('{{KEY}}', $key))
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    $raw | ConvertFrom-Json -AsHashtable
}
function NewIntent($Actor, [string]$Label, [decimal]$Amount) {
    $intent = @{actor=$Actor;key="$run-$Label";amount=$Amount;label=$Label;attempted=$false;lastStatus=$null;lastCode=$null}
    $intents.Add($intent)
    return $intent
}
function StartCheckout($Intent) {
    $message = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post, 'http://127.0.0.1:18080/api/checkout')
    $message.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$Intent.actor.token)
    [void]$message.Headers.TryAddWithoutValidation('Idempotency-Key',$Intent.key)
    $message.Content = [Net.Http.StringContent]::new((@{expectedAmount=$Intent.amount} | ConvertTo-Json -Compress),
        [Text.Encoding]::UTF8,'application/json')
    $Intent.attempted=$true
    $operation=@{intent=$Intent;message=$message;task=$http.SendAsync($message);finished=$false}
    $allPending.Add($operation)
    return $operation
}
function FinishCheckout($Pending) {
    try {
        $response = $Pending.task.GetAwaiter().GetResult()
        try {
            $outcome=@{status=[int]$response.StatusCode;body=($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json -AsHashtable)}
        } finally { $response.Dispose() }
    } catch { $outcome=@{status=0;body=@{code='TRANSPORT_OR_INVALID_JSON'}} }
    finally { $Pending.message.Dispose(); $Pending.finished=$true }
    $Pending.intent.lastStatus=$outcome.status
    $Pending.intent.lastCode=$outcome.body['code']
    return $outcome
}
function CleanupIntents([object[]]$Targets, [int]$Seconds=90) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $allSettled = $true
        foreach ($intent in $Targets) {
            if([DateTime]::UtcNow -ge $deadline){return $false}
            try {
                $fact = IntentFact $intent
                if ($null -eq $fact) {
                    # Only a known pre-intent validation rejection proves no job will arrive later.
                    # Transport failure + missing row is unknown, not successful cleanup.
                    $preIntentRejected=$intent.lastStatus -eq 400 -and $intent.lastCode -in @(
                        'INSUFFICIENT_STOCK','CART_EMPTY','PRICE_CHANGED','INVALID_QUANTITY','SKU_NOT_ON_SALE')
                    if($intent.attempted -and -not $preIntentRejected){$allSettled=$false}
                    continue
                }
                if ($null -eq $fact.orderStatus) {
                    if ($fact.checkoutStatus -ne 2) { $allSettled=$false }
                } elseif ($fact.orderStatus -eq 1) {
                    $allSettled=$false
                    $null = Api POST "/api/orders/$($fact.orderId)/cancel" $intent.actor
                } elseif ($fact.orderStatus -notin @(3,4) -or $fact.reservationStatus -notin @(3,4) -or
                        $fact.activeLocks -ne 0 -or $fact.confirmedLocks -ne 0 -or $fact.checkoutStatus -eq 0) {
                    $allSettled=$false
                }
            } catch { $allSettled=$false }
        }
        if ($allSettled) { return $true }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    return $false
}
function ResetSyntheticCart($Actor) {
    $cart = Api GET '/api/cart' $Actor
    Check 'fixture-cart-readable' ($cart.status -eq 200)
    foreach ($line in @($cart.body.items)) {
        $removed = Api DELETE "/api/cart/items/$($line.skuId)" $Actor
        Check 'fixture-cart-line-removed-via-api' ($removed.status -eq 200)
    }
}
try {
    foreach($port in @(18881,18882)) {
        $proxy = ProxyState $port
        Check "proxy-$port-starts-without-existing-fault-or-held-traffic" ($proxy.mode -eq 'pass' -and $proxy.remaining -eq 0 -and $proxy.held -eq 0)
    }
    $baseline = @(Stock)
    Check 'fixed-fixture-has-primary-100-secondary-at-least-one-and-no-locks' ($baseline.Count -eq 2 -and
        $baseline[0].skuId -eq 1001 -and $baseline[0].stock -ge 1 -and $baseline[0].locked -eq 0 -and
        $baseline[1].skuId -eq 1002 -and $baseline[1].stock -eq 100 -and $baseline[1].locked -eq 0)
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect=$false; $handler.UseProxy=$false; $handler.MaxConnectionsPerServer=64
    $http = [Net.Http.HttpClient]::new($handler)
    $http.Timeout=[TimeSpan]::FromSeconds(15)
    $script:stage='prepare-31-users-and-carts-before-holder'
    foreach ($index in 0..30) {
        $password=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
        $auth=Api POST '/api/auth/register' $null @{username="stock_$($run.Substring(0,20))_$index";password=$password}
        Check "register-$index" ($auth.status -eq 201 -and $auth.body.token -and $auth.body.userId)
        $actor=@{userId=[long]$auth.body.userId;token=[string]$auth.body.token}
        $actors.Add($actor); $password=$null; $auth=$null
        $quantity=if($index -eq 0){90}else{1}
        $cart=Api POST '/api/cart/items' $actor @{skuId=1002;quantity=$quantity}
        Check "cart-$index" ($cart.status -eq 200 -and @($cart.body.items).Count -eq 1 -and $cart.body.items[0].quantity -eq $quantity)
        $actor.amount=[decimal]$cart.body.selectedAmount
    }
    $script:stage='hold-90-through-real-checkout'
    $holder=NewIntent $actors[0] holder $actors[0].amount
    $heldOrder=FinishCheckout (StartCheckout $holder)
    Check 'holder-reserved-90' ($heldOrder.status -eq 200 -and $heldOrder.body.state -eq 'RESERVED')
    $stock=@(Stock)
    Check 'ten-units-remain-before-contention' ($stock[1].stock -eq 10 -and $stock[1].locked -eq 90 -and $stock[1].activeLocks -eq 90)

    $script:stage='C01-30-independent-real-checkouts'
    $pending=[Collections.Generic.List[object]]::new()
    foreach($index in 1..30) { $pending.Add((StartCheckout (NewIntent $actors[$index] "race-$index" $actors[$index].amount))) }
    foreach($operation in $pending) {
        $outcome=FinishCheckout $operation
        $class=if($outcome.status -eq 200 -and $outcome.body.state -eq 'RESERVED'){'success'}
            elseif(($outcome.status -eq 200 -and $outcome.body.state -eq 'FAILED') -or
                   ($outcome.status -eq 400 -and $outcome.body.code -eq 'INSUFFICIENT_STOCK')){'businessRejected'}
            else{'unknownOrInfrastructure'}
        $counts[$class]++
        $raceEvidence.Add(@{request=$operation.intent.label;userId=[string]$operation.intent.actor.userId;
            classification=$class;httpStatus=$outcome.status;state=$outcome.body['state'];code=$outcome.body['code'];orderId=$outcome.body['orderId']})
    }
    Check 'C01-exactly-ten-successes-twenty-business-rejections-no-infrastructure-errors' (
        $counts.success -eq 10 -and $counts.businessRejected -eq 20 -and $counts.unknownOrInfrastructure -eq 0)
    $stock=@(Stock)
    Check 'C01-no-oversell-and-lock-records-match' ($stock[1].stock -eq 0 -and $stock[1].locked -eq 100 -and $stock[1].activeLocks -eq 100)
    Check 'C01-real-cancel-restores-contender-units' (CleanupIntents @($intents | Where-Object label -like 'race-*') 30)
    $stock=@(Stock)
    Check 'holder-remains-and-ten-units-restored' ($stock[1].stock -eq 10 -and $stock[1].locked -eq 90)

    # Reverse cart insertion order on the second run; Inventory still locks ascending SKU IDs.
    foreach($round in 1..2) {
        $script:stage="N06-N07-two-sku-rollback-$round"
        $target=$actors[1]; $drainer=$actors[2]
        ResetSyntheticCart $target; ResetSyntheticCart $drainer
        $skuOrder=if($round -eq 1){@(1001,1002)}else{@(1002,1001)}
        foreach($sku in $skuOrder) {
            $cart=Api POST '/api/cart/items' $target @{skuId=$sku;quantity=1}
            Check "multi-$round-cart-$sku" ($cart.status -eq 200)
        }
        $multi=NewIntent $target "multi-$round" ([decimal]$cart.body.selectedAmount)
        $drainCart=Api POST '/api/cart/items' $drainer @{skuId=1002;quantity=10}
        Check "drain-$round-cart" ($drainCart.status -eq 200)
        $drain=NewIntent $drainer "drain-$round" ([decimal]$drainCart.body.selectedAmount)
        $faultOwned=$true; SetProxy 'hold-before'
        $window=[Diagnostics.Stopwatch]::StartNew()
        $multiPending=StartCheckout $multi
        do { $proxy=ProxyState; if($proxy.held -eq 1){break}; Start-Sleep -Milliseconds 20 } while($window.ElapsedMilliseconds -lt 1500)
        $heldAtMs=$window.ElapsedMilliseconds
        $heldSequence=if($proxy.held -eq 1){$proxy.lastFault.sequence}else{$null}
        $drainPending=$null
        if($proxy.held -eq 1 -and $window.ElapsedMilliseconds -lt 2300) {
            $drainPending=StartCheckout $drain
            while(-not $drainPending.task.IsCompleted -and $window.ElapsedMilliseconds -lt 2300) { Start-Sleep -Milliseconds 10 }
        }
        $drainReady=$null -ne $drainPending -and $drainPending.task.IsCompleted
        $releaseRequestedAtMs=$window.ElapsedMilliseconds
        # Release BEFORE waiting for a slow drainer or asserting anything. The total window
        # is capped below the unchanged 3-second Feign timeout; a miss is not a test pass.
        SetProxy pass; $faultOwned=$false
        $releaseCompletedAtMs=$window.ElapsedMilliseconds
        $window.Stop()
        $windowMissed=-not $drainReady -or $releaseCompletedAtMs -ge 2500
        $windowEvidence=@{round=$round;insertionOrder=$skuOrder;heldObservedAtMs=$heldAtMs;
            releaseRequestedAtMs=$releaseRequestedAtMs;releaseCompletedAtMs=$releaseCompletedAtMs;
            faultSequence=$heldSequence;windowMissed=$windowMissed}
        $rollbackEvidence.Add($windowEvidence)
        $drained=if($null -ne $drainPending){FinishCheckout $drainPending}else{@{status=0;body=@{}}}
        $rejected=FinishCheckout $multiPending
        $completedFault=ProxyState
        $windowEvidence.lastFault=$completedFault.lastFault
        Check "multi-$round-reached-inventory-after-commerce-precheck" ($null -ne $heldSequence)
        Check "multi-$round-release-within-bounded-window-not-timeout" (-not $windowMissed)
        Check "drain-$round-consumes-last-ten-before-held-reserve" ($drained.status -eq 200 -and $drained.body.state -eq 'RESERVED')
        Check "multi-$round-deterministically-fails-not-timeout-compensation" ($rejected.status -eq 200 -and $rejected.body.state -eq 'FAILED')
        Check "multi-$round-same-held-inventory-request-completed-with-rejection" (
            $completedFault.lastFault.sequence -eq $heldSequence -and $completedFault.lastFault.completed -and
            $completedFault.lastFault.businessStatus -eq 'REJECTED')
        $fact=IntentFact $multi
        $stock=@(Stock)
        Check "multi-$round-transaction-left-no-lock-rows" ($null -ne $fact -and $fact.reservationStatus -eq 4 -and $fact.allLocks -eq 0)
        Check "multi-$round-first-sku-rolled-back-and-short-sku-not-negative" (
            $stock[0].stock -eq $baseline[0].stock -and $stock[0].locked -eq 0 -and $stock[1].stock -eq 0 -and $stock[1].locked -eq 100)
        $windowEvidence.orderId=$fact.orderId; $windowEvidence.orderStatus=$fact.orderStatus
        $windowEvidence.reservationStatus=$fact.reservationStatus; $windowEvidence.lockRows=$fact.allLocks; $windowEvidence.stock=$stock
        Check "drain-$round-canceled-via-api" (CleanupIntents @($drain,$multi) 30)
        $stock=@(Stock)
        Check "round-$round-ten-units-restored" ($stock[1].stock -eq 10 -and $stock[1].locked -eq 90)
    }
    $passed=$true
} catch {
    $failureStage=$script:stage
    $passed=$false
} finally {
    if($faultOwned) { try { SetProxy pass; $faultOwned=$false } catch { $passed=$false } }
    # Drain every started HTTP attempt before examining cleanup; never abandon a late create task.
    foreach($operation in $allPending) {
        if(-not $operation.finished){$null=FinishCheckout $operation}
    }
    $script:stage='cleanup-and-final-read-only-stock-verification'
    try {
        $cleaned=CleanupIntents @($intents.ToArray()) 90
        foreach($intent in $intents) {
            $fact=IntentFact $intent
            $cleanupEvidence.Add(@{label=$intent.label;userId=[string]$intent.actor.userId;fact=$fact})
        }
        $finalStock=@(Stock)
        $stockRestored=$null -ne $baseline -and $finalStock.Count -eq 2 -and
            $finalStock[0].stock -eq $baseline[0].stock -and $finalStock[1].stock -eq $baseline[1].stock -and
            $finalStock[0].locked -eq 0 -and $finalStock[1].locked -eq 0 -and
            $finalStock[0].activeLocks -eq 0 -and $finalStock[1].activeLocks -eq 0
        $checks.Add(@{name='finally-all-owned-orders-terminal-and-stock-restored-via-cancel';passed=($cleaned -and $stockRestored)})
        if(-not $cleaned -or -not $stockRestored){$passed=$false}
    } catch { $passed=$false; $cleaned=$false }
    if(-not $passed -and $null -eq $failureStage){$failureStage=$script:stage}
    if($null -ne $http){$http.Dispose()}
    foreach($actor in $actors){$actor.token=$null}
    $internal=$null; $scenarioLock.Dispose()
}
$report=[ordered]@{runId=$run;project=$ProjectName;passed=$passed;stoppedAt=$failureStage;
    startedAtUtc=$started.ToString('o');finishedAtUtc=[DateTime]::UtcNow.ToString('o');
    scope='C01/N06/N07 single-instance real HTTP and MySQL; no business SQL writes or capacity claim';
    counts=$counts;baseline=$baseline;finalStock=$finalStock;cleanupSettled=$cleaned;proxyRestored=(-not $faultOwned);syntheticUsers=$actors.Count;
    checkoutResponses=@($raceEvidence.ToArray());multiSkuRollback=@($rollbackEvidence.ToArray());
    cleanup=@($cleanupEvidence.ToArray());assertions=@($checks.ToArray());
    retainedData='Created synthetic users and order/checkout/lock facts remain; inspect cleanup if failed. No credentials exported.'}
if($EvidencePath){Save-AcceptanceJson -Path ([IO.Path]::GetFullPath($EvidencePath)) -Value $report}
$report | ConvertTo-Json -Depth 10
if(-not $passed){exit 1}
