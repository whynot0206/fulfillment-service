#requires -Version 7.4
<# Dedicated acceptance deployment only. Injects HTTP faults and restarts owned services.
   Creates synthetic records through APIs; SQL is strictly read-only evidence.
   Requires start-fault-proxies.ps1 and start-backend.ps1 -UseFaultProxies beforehand.
#>
param([Parameter(Mandatory)][string]$JavaPath,
      [string]$ProjectName = 'fulfillment-acceptance', [string]$EvidencePath)
. (Join-Path $PSScriptRoot 'common.ps1')
$context = Get-AcceptanceContext $ProjectName
$mysql = Get-AcceptanceContainer $context 'mysql'
$internal = Get-AcceptanceSecret 'ACCEPTANCE_INTERNAL_SERVICE_TOKEN'
$backendRegistry = Get-Content (Join-Path $context.Runtime 'backend-processes.json') -Raw | ConvertFrom-Json
$proxyRegistry = Get-Content (Join-Path $context.Runtime 'fault-proxies.json') -Raw | ConvertFrom-Json
foreach ($registry in @($backendRegistry,$proxyRegistry)) {
    if ($registry.project -ne $ProjectName) { throw 'HTTP deployment ownership mismatch; no test traffic sent.' }
    foreach ($record in $registry.processes) {
        $owned = Get-AcceptanceOwnedProcess $record
        $listeners = @(Get-NetTCPConnection -LocalPort $record.port -State Listen -ErrorAction SilentlyContinue)
        if (-not $owned -or $listeners.Count -eq 0 -or @($listeners | Where-Object { $_.OwningProcess -ne $owned.Id -or $_.LocalAddress -ne '127.0.0.1' }).Count -gt 0) {
            throw 'HTTP listeners must be loopback and owned by this acceptance project.'
        }
    }
}
foreach ($pair in @(@('gateway',18080),@('order-service',18081),@('commerce-service',18084))) {
    if (@($backendRegistry.processes | Where-Object { $_.service -eq $pair[0] -and $_.port -eq $pair[1] }).Count -ne 1) { throw 'Recovery test requires the documented acceptance ports.' }
}
foreach ($port in @(18881,18882)) {
    if (@($proxyRegistry.processes | Where-Object port -eq $port).Count -ne 1) { throw 'Owned fault proxies required.' }
}
foreach ($record in @($backendRegistry.processes | Where-Object service -in @('order-service','commerce-service'))) {
    if (-not $record.PSObject.Properties['usesFaultProxy'] -or -not $record.usesFaultProxy) {
        throw 'Restart owned Order and Commerce with -UseFaultProxies before recovery tests.'
    }
}
$run = [Guid]::NewGuid().ToString('N')
$checks = [Collections.Generic.List[object]]::new()
$orders = [Collections.Generic.List[object]]::new()
$passed = $false
$stage = 'initialization'
function Assert-Recovery([string]$Name, [bool]$Condition) {
    $checks.Add(@{name=$Name;passed=$Condition})
    if (-not $Condition) { throw "Recovery assertion failed: $Name" }
}
function Api([string]$Method, [string]$Path, $Body = $null, [string]$Key = '') {
    $headers = @{Authorization="Bearer $script:token"}
    if ($Key) { $headers['Idempotency-Key'] = $Key }
    $args = @{Uri="http://127.0.0.1:18080$Path";Method=$Method;Headers=$headers;
        SkipHttpErrorCheck=$true;NoProxy=$true;TimeoutSec=15}
    if ($null -ne $Body) { $args.Body = ConvertTo-Json $Body -Depth 8 -Compress; $args.ContentType='application/json' }
    $response = Invoke-WebRequest @args
    @{status=[int]$response.StatusCode;body=($response.Content | ConvertFrom-Json -AsHashtable)}
}
function Fault([int]$Port, [string]$Mode, [string]$Path='', [int]$Remaining=0) {
    Invoke-RestMethod "http://127.0.0.1:$Port/__fault__/state" -Method Post -NoProxy -TimeoutSec 3 `
        -Headers @{'X-Fault-Token'=$internal} -ContentType 'application/json' `
        -Body (@{mode=$Mode;path=$Path;remaining=$Remaining} | ConvertTo-Json -Compress)
}
function FaultState([int]$Port) {
    Invoke-RestMethod "http://127.0.0.1:$Port/__fault__/state" -NoProxy -TimeoutSec 3 -Headers @{'X-Fault-Token'=$internal}
}
function Restart-Backends {
    & (Join-Path $PSScriptRoot 'start-backend.ps1') -ProjectName $ProjectName -JavaPath $JavaPath -OrderTimeoutSeconds 180 -UseFaultProxies
}
function Read-Fact([string]$Sql) { Invoke-AcceptanceSql $mysql $Sql }
function Wait-Fact([string]$Name, [string]$Sql, [string]$Expected, [int]$Seconds=90) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $actual = Read-Fact $Sql
        if ($actual -eq $Expected) { Assert-Recovery $Name $true; return }
        Start-Sleep -Milliseconds 700
    } while ([DateTime]::UtcNow -lt $deadline)
    Assert-Recovery $Name $false
}
$script:token = ''
$http = $null
try {
    $null = Fault 18881 pass
    $null = Fault 18882 pass
    $auth = Api POST '/api/auth/register' @{username=('recovery_' + $run.Substring(0,20));password=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))}
    Assert-Recovery 'synthetic-user-created' ($auth.status -eq 201)
    $script:token = $auth.body.token
    $userId = [long]$auth.body.userId

    $stage = 'R03-response-loss-and-commerce-restart'
    $cart = Api POST '/api/cart/items' @{skuId=1004;quantity=1}
    Assert-Recovery 'recovery-cart-added' ($cart.status -eq 200)
    $amount = [decimal]$cart.body.selectedAmount
    $key = "$run-lost"
    $null = Fault 18881 'drop-response' '/internal/orders/create-and-resolve' 100
    $unknown = Api POST '/api/checkout' @{expectedAmount=$amount} $key
    Assert-Recovery 'lost-response-is-unknown-not-rejection' ($unknown.status -eq 409 -and $unknown.body.code -in @('CHECKOUT_RESULT_UNKNOWN','CHECKOUT_IN_PROGRESS'))
    & (Join-Path $PSScriptRoot 'stop-backend.ps1') -ProjectName $ProjectName -Services commerce-service
    $fact = Read-Fact "SELECT CONCAT(CAST(order_id AS CHAR),'|',status,'|',IF(request_payload IS NULL,0,1)) FROM fulfillment_commerce.checkout_request WHERE user_id=$userId AND idempotency_key='$key';"
    $parts = $fact -split '\|'
    Assert-Recovery 'unknown-intent-has-durable-order-and-snapshot' ($parts.Length -eq 3 -and $parts[0] -match '^[1-9][0-9]*$' -and $parts[1] -eq '0' -and $parts[2] -eq '1')
    $recoveredId = $parts[0]
    $orders.Add(@{branch='lost-response';orderId=$recoveredId})
    $null = Fault 18881 pass
    Restart-Backends
    Wait-Fact 'intent-completes-after-commerce-restart' "SELECT status FROM fulfillment_commerce.checkout_request WHERE user_id=$userId AND idempotency_key='$key';" '1'
    # A user edit after the unknown response must not be re-priced into a new intent or erased.
    $changed = Api PUT '/api/cart/items/1004' @{quantity=2}
    Assert-Recovery 'cart-can-be-modified-after-recovery' ($changed.status -eq 200)
    $replay = Api POST '/api/checkout' @{expectedAmount=$amount} $key
    Assert-Recovery 'same-key-recovers-exact-original-order' ($replay.status -eq 200 -and $replay.body.orderId -is [string] -and $replay.body.orderId -ceq $recoveredId -and $replay.body.replayed -eq $true)
    Assert-Recovery 'recovered-response-warns-cart-retained' ($replay.body.cartCleanupRequired -eq $true)
    $retained = Api GET '/api/cart'
    Assert-Recovery 'recovery-keeps-later-cart-edit' (@($retained.body.items).Count -eq 1 -and $retained.body.items[0].quantity -eq 2)
    Assert-Recovery 'unknown-retry-creates-one-order' ((Read-Fact "SELECT COUNT(*) FROM fulfillment_order.``order`` WHERE user_id=$userId;") -eq '1')
    $cancel = Api POST "/api/orders/$recoveredId/cancel"
    Assert-Recovery 'recovered-order-cancelable' ($cancel.status -eq 200)
    $null = Api DELETE '/api/cart/items/1004'

    foreach ($cartRace in @('quantity-edit','delete-and-readd','quantity-aba')) {
        $stage = "cart-cleanup-$cartRace"
        $cart = Api POST '/api/cart/items' @{skuId=1004;quantity=1}
        $raceAmount = [decimal]$cart.body.selectedAmount
        $null = Fault 18881 'hold-after' '/internal/orders' 1
        $http = [Net.Http.HttpClient]::new()
        $http.Timeout = [TimeSpan]::FromSeconds(15)
        $http.DefaultRequestHeaders.Add('Authorization',"Bearer $script:token")
        $http.DefaultRequestHeaders.Add('Idempotency-Key',"$run-$cartRace")
        $content = [Net.Http.StringContent]::new((@{expectedAmount=$raceAmount} | ConvertTo-Json -Compress),[Text.Encoding]::UTF8,'application/json')
        $pendingCheckout = $http.PostAsync('http://127.0.0.1:18080/api/checkout',$content)
        $heldDeadline = [DateTime]::UtcNow.AddSeconds(2)
        do { $proxy=FaultState 18881; if ($proxy.held -gt 0) { break }; Start-Sleep -Milliseconds 30 } while ([DateTime]::UtcNow -lt $heldDeadline)
        Assert-Recovery "$cartRace.checkout-result-held" ($proxy.held -eq 1)
        if ($cartRace -eq 'quantity-edit') {
            $edited = Api PUT '/api/cart/items/1004' @{quantity=2}
            Assert-Recovery "$cartRace.mutation-succeeded" ($edited.status -eq 200)
        } elseif ($cartRace -eq 'quantity-aba') {
            $edited = Api PUT '/api/cart/items/1004' @{quantity=2}
            $restored = Api PUT '/api/cart/items/1004' @{quantity=1}
            Assert-Recovery "$cartRace.mutation-succeeded" ($edited.status -eq 200 -and $restored.status -eq 200)
        } else {
            $deleted = Api DELETE '/api/cart/items/1004'
            $edited = Api POST '/api/cart/items' @{skuId=1004;quantity=1}
            Assert-Recovery "$cartRace.mutation-succeeded" ($deleted.status -eq 200 -and $edited.status -eq 200)
        }
        $null = Fault 18881 pass
        $completed = $pendingCheckout.GetAwaiter().GetResult()
        $raceResult = $completed.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        Assert-Recovery "$cartRace.checkout-succeeds-with-cleanup-warning" ([int]$completed.StatusCode -eq 200 -and $raceResult.state -eq 'RESERVED' -and $raceResult.cartCleanupRequired)
        $raceId = [string]$raceResult.orderId
        $orders.Add(@{branch=$cartRace;orderId=$raceId})
        $retained = Api GET '/api/cart'
        $expectedQuantity = if ($cartRace -eq 'quantity-edit') { 2 } else { 1 }
        Assert-Recovery "$cartRace.later-cart-line-not-deleted" (@($retained.body.items).Count -eq 1 -and $retained.body.items[0].quantity -eq $expectedQuantity)
        $cancel = Api POST "/api/orders/$raceId/cancel"
        Assert-Recovery "$cartRace.original-order-canceled" ($cancel.status -eq 200)
        $null = Api DELETE '/api/cart/items/1004'
        $http.Dispose()
        $http = $null
    }

    foreach ($crashMode in @('hold-before','hold-after')) {
    $stage = "R04-order-crash-$crashMode"
    $crashId = (900000000000000000L + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()).ToString()
    $orders.Add(@{branch="crash-$crashMode";orderId=$crashId})
    $null = Fault 18882 $crashMode '/internal/inventory/reserve' 1
    $request = @{orderId=[long]$crashId;userId=$userId;totalAmount=[decimal]1;timeoutSeconds=180;
        items=@(@{skuId=1003;spuId=2;count=1;price=[decimal]1;nameSnapshot='Acceptance crash fixture';specSnapshot='{}'})}
    $http = [Net.Http.HttpClient]::new()
    $http.Timeout = [TimeSpan]::FromSeconds(20)
    $http.DefaultRequestHeaders.Add('X-Internal-Service-Token',$internal)
    $content = [Net.Http.StringContent]::new(($request | ConvertTo-Json -Depth 8 -Compress),[Text.Encoding]::UTF8,'application/json')
    $pending = $http.PostAsync('http://127.0.0.1:18081/internal/orders',$content)
    $heldDeadline = [DateTime]::UtcNow.AddSeconds(2)
    do { $proxy = FaultState 18882; if ($proxy.held -gt 0) { break }; Start-Sleep -Milliseconds 50 } while ([DateTime]::UtcNow -lt $heldDeadline)
    Assert-Recovery "$crashMode.reserve-request-held" ($proxy.held -eq 1)
    & (Join-Path $PSScriptRoot 'stop-backend.ps1') -ProjectName $ProjectName -Services order-service
    try { $null = $pending.GetAwaiter().GetResult() } catch { }
    Assert-Recovery "$crashMode.crash-leaves-real-reserving-intent" ((Read-Fact "SELECT CONCAT(status,'|',reservation_status) FROM fulfillment_order.``order`` WHERE order_id=$crashId;") -eq '1|0')
    $expectedLocks = if ($crashMode -eq 'hold-after') { '1' } else { '0' }
    Assert-Recovery "$crashMode.pre-recovery-inventory-fact" ((Read-Fact "SELECT COUNT(*) FROM fulfillment_inventory.sku_stock_lock WHERE order_id=$crashId AND status=1;") -eq $expectedLocks)
    Restart-Backends
    Wait-Fact "$crashMode.stale-reserving-cancels-and-compensates" "SELECT CONCAT(status,'|',reservation_status) FROM fulfillment_order.``order`` WHERE order_id=$crashId;" '3|3' 95
    $beforeRelease = FaultState 18882
    Assert-Recovery "$crashMode.request-remains-held-until-after-compensation" ($beforeRelease.held -eq 1)
    Assert-Recovery "$crashMode.inventory-cancel-fence-committed" ((Read-Fact "SELECT status FROM fulfillment_inventory.inventory_reservation_fence WHERE order_id=$crashId;") -eq '2')
    $null = Fault 18882 pass
    Start-Sleep -Seconds 2
    if ($crashMode -eq 'hold-before') {
        $afterRelease = FaultState 18882
        Assert-Recovery 'late-reserve-was-actually-forwarded-and-rejected' ($afterRelease.lastFault.completed -eq $true -and $afterRelease.lastFault.sequence -eq $beforeRelease.lastFault.sequence -and $afterRelease.lastFault.businessStatus -eq 'REJECTED')
    }
    Assert-Recovery "$crashMode.late-reserve-cannot-relock-stock" ((Read-Fact "SELECT COUNT(*) FROM fulfillment_inventory.sku_stock_lock WHERE order_id=$crashId AND status IN (1,3);") -eq '0')
    $repeated = Invoke-WebRequest 'http://127.0.0.1:18081/internal/orders' -Method Post -NoProxy -TimeoutSec 10 `
        -Headers @{'X-Internal-Service-Token'=$internal} -ContentType 'application/json' -Body ($request | ConvertTo-Json -Depth 8 -Compress) -SkipHttpErrorCheck
    $repeatBody = $repeated.Content | ConvertFrom-Json
    Assert-Recovery "$crashMode.replay-cannot-revive-canceled-order" ($repeatBody.state -eq 'COMPENSATED')
    $http.Dispose()
    $http = $null
    }

    $stage = 'R05-confirmation-failure-and-retry'
    $cart = Api POST '/api/cart/items' @{skuId=1003;quantity=1}
    $checkout = Api POST '/api/checkout' @{expectedAmount=[decimal]$cart.body.selectedAmount} "$run-outbox"
    Assert-Recovery 'outbox-order-reserved' ($checkout.status -eq 200 -and $checkout.body.state -eq 'RESERVED')
    $paidId = [string]$checkout.body.orderId
    $orders.Add(@{branch='outbox-retry';orderId=$paidId})
    $null = Fault 18882 reject '/internal/inventory/confirm' 100
    $payment = Api POST "/api/payments/orders/$paidId/mock-success"
    Assert-Recovery 'payment-commits-despite-confirmation-outage' ($payment.status -eq 200)
    Wait-Fact 'failed-confirmation-retains-pending-event' "SELECT COUNT(*) FROM fulfillment_order.order_outbox_event WHERE biz_key='$paidId' AND status=0 AND retry_count>0;" '1' 15
    Assert-Recovery 'paid-fact-survives-outbox-retry' ((Read-Fact "SELECT status FROM fulfillment_order.``order`` WHERE order_id=$paidId;") -eq '2')
    Assert-Recovery 'stock-remains-locked-before-confirmation' ((Read-Fact "SELECT COUNT(*) FROM fulfillment_inventory.sku_stock_lock WHERE order_id=$paidId AND status=1;") -eq '1')
    $null = Fault 18882 pass
    Wait-Fact 'outbox-event-eventually-sent' "SELECT COUNT(*) FROM fulfillment_order.order_outbox_event WHERE biz_key='$paidId' AND status=2 AND lease_owner IS NULL AND lease_until IS NULL;" '1' 30
    Assert-Recovery 'retry-confirms-exactly-one-reservation' ((Read-Fact "SELECT CONCAT(COUNT(*),'|',SUM(count),'|',MIN(status)) FROM fulfillment_inventory.sku_stock_lock WHERE order_id=$paidId;") -eq '1|1|3')
    $again = Api POST "/api/payments/orders/$paidId/mock-success"
    Assert-Recovery 'duplicate-payment-still-accepted' ($again.status -eq 200)
    Assert-Recovery 'duplicate-payment-does-not-create-second-event' ((Read-Fact "SELECT COUNT(*) FROM fulfillment_order.order_outbox_event WHERE biz_key='$paidId';") -eq '1')
    $passed = $true
} finally {
    foreach ($port in @(18881,18882)) { try { $null = Fault $port pass } catch { Write-Warning 'Could not restore fault proxy; inspect owned deployment.' } }
    if ($http) { $http.Dispose() }
    $script:token = ''
    $result = @{recordedAt=[DateTime]::UtcNow.ToString('o');passed=$passed;stage=$stage;
        project=$ProjectName;checks=@($checks);orders=@($orders);scope='Single-instance, real HTTP faults; no fabricated database business writes'}
    if ($EvidencePath) { Save-AcceptanceJson $EvidencePath $result }
    Write-Output "Recovery checks: $($checks.Count), passed=$passed, last stage=$stage."
}
