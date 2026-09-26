#requires -Version 7.4
<#
N01/N02/N08: owned isolated deployment only; first load process secrets using use-secrets.ps1.
Requires F07 Completed and both proxies idle/pass. Never changes faults, services or business SQL.
Creates two synthetic users/two one-unit SKU 1003 orders: one paid, the other canceled/released.
Do not run with transcript/HTTP debug logging. Evidence allowlists IDs, states and assertions only.
#>
[CmdletBinding()]
param([string]$ProjectName='fulfillment-acceptance', [ValidateRange(10,120)][int]$SettleTimeoutSeconds=45, [string]$EvidencePath)
. (Join-Path $PSScriptRoot 'common.ps1')
$context=Get-AcceptanceContext $ProjectName
$runId=[guid]::NewGuid().ToString('N')
$checks=[Collections.Generic.List[object]]::new()
$fixtures=[Collections.Generic.List[object]]::new()
$users=[Collections.Generic.List[object]]::new()
$cleanup=[Collections.Generic.List[object]]::new()
$stage='preflight'; $passed=$false; $trafficAllowed=$false; $runLock=$null; $mysql=$null
$internal=$null; $jwtSecret=$null; $paymentSecret=$null; $tokenA=$null; $tokenB=$null
$started=[datetime]::UtcNow

function Check([string]$Name,[bool]$Condition,[Nullable[int]]$Status=$null) {
    $checks.Add(@{name=$Name;passed=$Condition;httpStatus=$Status})
    if(-not $Condition){throw 'A security-boundary assertion failed.'}
}
function Assert-OwnedListeners {
    $backend=Get-Content (Join-Path $context.Runtime 'backend-processes.json') -Raw | ConvertFrom-Json
    $proxies=Get-Content (Join-Path $context.Runtime 'fault-proxies.json') -Raw | ConvertFrom-Json
    if($backend.project -ne $ProjectName -or $proxies.project -ne $ProjectName){throw 'HTTP registry ownership mismatch.'}
    foreach($pair in @(@('gateway',18080),@('order-service',18081),@('inventory-service',18082),@('payment-service',18083),@('commerce-service',18084))){
        if(@($backend.processes | Where-Object {$_.service -eq $pair[0] -and $_.port -eq $pair[1]}).Count -ne 1){throw 'Expected documented owned backend ports.'}
    }
    foreach($port in @(18881,18882)){
        if(@($proxies.processes | Where-Object port -eq $port).Count -ne 1){throw 'Both owned fault proxies are required.'}
    }
    foreach($record in @($backend.processes)+@($proxies.processes)){
        $owned=Get-AcceptanceOwnedProcess $record
        $listeners=@(Get-NetTCPConnection -LocalPort $record.port -State Listen -ErrorAction SilentlyContinue)
        if(-not $owned -or $listeners.Count -eq 0 -or @($listeners | Where-Object {$_.OwningProcess -ne $owned.Id -or $_.LocalAddress -ne '127.0.0.1'}).Count -gt 0){
            throw 'Every tested listener must be loopback and owned by this acceptance project.'
        }
    }
}
function Read-Fact([string]$Sql){
    if($Sql -notmatch '^\s*SELECT\b' -or $Sql -match '(?i)\b(UPDATE|INSERT|DELETE|ALTER|TRUNCATE|CALL)\b'){throw 'Evidence SQL must be read-only.'}
    Invoke-AcceptanceSql $mysql $Sql -Operation 'Read security fixture facts'
}
function Assert-ProxiesPass {
    foreach($port in @(18881,18882)){
        try {$proxy=Invoke-RestMethod "http://127.0.0.1:$port/__fault__/state" -NoProxy -TimeoutSec 3 -Headers @{'X-Fault-Token'=$internal} -Verbose:$false -Debug:$false}
        catch {throw 'Owned fault-proxy state could not be verified.'}
        Check "preflight.proxy-$port-pass" ($proxy.mode -eq 'pass' -and $proxy.held -eq 0)
    }
}
function Http([string]$Case,[int]$Port,[string]$Method,[string]$Path,[int]$Expected,[string]$Token='',$Body=$null,[hashtable]$Headers=@{}){
    $script:stage=$Case
    if($Port -notin @(18080,18081,18082,18083,18084) -or $Path -notmatch '^/api/|^/internal/'){throw 'Only documented owned endpoints are allowed.'}
    $requestHeaders=@{Accept='application/json'}
    foreach($name in $Headers.Keys){$requestHeaders[$name]=$Headers[$name]}
    if($Token){$requestHeaders.Authorization="Bearer $Token"}
    $arguments=@{Uri="http://127.0.0.1:$Port$Path";Method=$Method;Headers=$requestHeaders;SkipHttpErrorCheck=$true;NoProxy=$true;MaximumRedirection=0;TimeoutSec=15;Verbose=$false;Debug=$false}
    if($null -ne $Body){$arguments.ContentType='application/json; charset=utf-8';$arguments.Body=ConvertTo-Json -InputObject $Body -Depth 8 -Compress}
    try {$response=Invoke-WebRequest @arguments} catch {throw 'HTTP transport failed; request/response content was suppressed.'}
    Check "$Case.http" ([int]$response.StatusCode -eq $Expected) ([int]$response.StatusCode)
    if([string]::IsNullOrWhiteSpace($response.Content)){return @{}}
    try {return ConvertFrom-Json -InputObject $response.Content -AsHashtable} catch {throw 'Response JSON unreadable; content suppressed.'}
}
function Encode-Base64Url([byte[]]$Bytes){return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+','-').Replace('/','_')}
function Decode-Base64Url([string]$Value){
    $value=$Value.Replace('-','+').Replace('_','/')
    $value+='=' * ((4-$value.Length%4)%4)
    return ,([Convert]::FromBase64String($value))
}
function Hmac-Bytes([string]$Secret,[string]$Canonical){
    $keyBytes=[Text.Encoding]::UTF8.GetBytes($Secret)
    $hmac=[Security.Cryptography.HMACSHA256]::new($keyBytes)
    try {return ,($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($Canonical)))}
    finally {$hmac.Dispose();[Array]::Clear($keyBytes,0,$keyBytes.Length)}
}
function New-ExpiredJwt([string]$Original,[string]$Secret){
    $parts=$Original -split '[.]'
    if($parts.Count -ne 3){throw 'Unexpected registration JWT shape.'}
    $payload=[Text.Encoding]::UTF8.GetString((Decode-Base64Url $parts[1])) | ConvertFrom-Json -AsHashtable
    $now=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $payload.iat=$now-7200; $payload.exp=$now-3600
    $encoded=Encode-Base64Url ([Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Compress)))
    $inputText="$($parts[0]).$encoded"
    return "$inputText.$(Encode-Base64Url (Hmac-Bytes $Secret $inputText))"
}
function New-CallbackHeaders([string]$OrderId,[string]$TradeNo,[long]$Timestamp){
    $canonical=[string]::Join([char]10,@($OrderId,$TradeNo,[string]$Timestamp))
    $signature=[Convert]::ToHexString((Hmac-Bytes $paymentSecret $canonical)).ToLowerInvariant()
    return @{'X-Payment-Timestamp'="$Timestamp";'X-Payment-Signature'=$signature}
}
function Assert-Id([string]$Id){
    if($Id -notmatch '^[1-9][0-9]{0,18}$'){throw 'Invalid fixture identifier; no SQL generated.'}
}
function Order-Fact([string]$OrderId,[string]$UserId){
    Assert-Id $OrderId; Assert-Id $UserId
    $quotedOrder=[string][char]96+'order'+[char]96
    $sql=@"
SELECT JSON_OBJECT('orderId',CAST(o.order_id AS CHAR),'userId',CAST(o.user_id AS CHAR),
 'status',o.status,'reservation',o.reservation_status,'tradeNo',o.out_trade_no,
 'events',(SELECT COUNT(*) FROM fulfillment_order.order_outbox_event e WHERE e.biz_key='$OrderId' AND e.event_type='PAYMENT_CONFIRMED'),
 'sentEvents',(SELECT COUNT(*) FROM fulfillment_order.order_outbox_event e WHERE e.biz_key='$OrderId' AND e.event_type='PAYMENT_CONFIRMED' AND e.status=2),
 'lockRows',(SELECT COUNT(*) FROM fulfillment_inventory.sku_stock_lock l WHERE l.order_id=$OrderId),
 'lockedUnits',(SELECT COALESCE(SUM(l.count),0) FROM fulfillment_inventory.sku_stock_lock l WHERE l.order_id=$OrderId AND l.status=1),
 'releasedUnits',(SELECT COALESCE(SUM(l.count),0) FROM fulfillment_inventory.sku_stock_lock l WHERE l.order_id=$OrderId AND l.status=2),
 'confirmedUnits',(SELECT COALESCE(SUM(l.count),0) FROM fulfillment_inventory.sku_stock_lock l WHERE l.order_id=$OrderId AND l.status=3))
FROM fulfillment_order.$quotedOrder o WHERE o.order_id=$OrderId AND o.user_id=$UserId;
"@
    $value=Read-Fact $sql
    if(-not $value){throw 'Fixture order missing or has unexpected ownership.'}
    return $value | ConvertFrom-Json -AsHashtable
}
function New-Order([string]$Label,[string]$UserId){
    $cart=Http "$Label.add-sku" 18080 POST '/api/cart/items' 200 $tokenA @{skuId=1003;quantity=1}
    Check "$Label.one-unit" (@($cart.items).Count -eq 1 -and $cart.items[0].skuId -eq 1003 -and $cart.items[0].quantity -eq 1 -and $cart.selectedCount -eq 1)
    $amount=[decimal]$cart.selectedAmount
    $result=Http "$Label.checkout" 18080 POST '/api/checkout' 200 $tokenA @{expectedAmount=$amount} @{'Idempotency-Key'="$runId-$Label"}
    Check "$Label.exact-order-id" ($result.orderId -is [string])
    Assert-Id $result.orderId
    $fixture=@{branch=$Label;orderId=[string]$result.orderId;userId=$UserId;skuId=1003;totalAmount=$amount;final=$null}
    $fixtures.Add($fixture)
    Check "$Label.reserved" ($result.state -eq 'RESERVED')
    return $fixture
}
function Wait-Settled($Fixture,[bool]$Paid){
    $deadline=[datetime]::UtcNow.AddSeconds($SettleTimeoutSeconds)
    do {
        $fact=Order-Fact $Fixture.orderId $Fixture.userId
        $ok=if($Paid){
            $fact.status -eq 2 -and $fact.reservation -eq 1 -and $fact.events -eq 1 -and $fact.sentEvents -eq 1 -and $fact.lockRows -eq 1 -and $fact.confirmedUnits -eq 1 -and $fact.lockedUnits -eq 0 -and $fact.releasedUnits -eq 0
        }else{
            $fact.status -eq 3 -and $fact.reservation -eq 3 -and $fact.events -eq 0 -and $fact.lockRows -eq 1 -and $fact.releasedUnits -eq 1 -and $fact.lockedUnits -eq 0 -and $fact.confirmedUnits -eq 0 -and $null -eq $fact.tradeNo
        }
        if($ok){$Fixture.final=$fact;return}
        Start-Sleep -Milliseconds 400
    }while([datetime]::UtcNow -lt $deadline)
    throw 'Fixture facts did not converge within the bounded wait.'
}

try {
    Assert-OwnedListeners
    $infra=Get-Content (Join-Path $context.Runtime 'infrastructure.json') -Raw | ConvertFrom-Json
    $database=Get-Content (Join-Path $context.Runtime 'database.json') -Raw | ConvertFrom-Json
    $mysql=Get-AcceptanceContainer $context mysql
    $redis=Get-AcceptanceContainer $context redis
    Check 'preflight.owned-containers' ($infra.project -eq $ProjectName -and $database.project -eq $ProjectName -and $database.status -eq 'READY' -and $mysql -and $redis -and $mysql -eq $infra.mysqlContainer -and $mysql -eq $database.mysqlContainer -and $redis -eq $infra.redisContainer)
    foreach($container in @($mysql,$redis)){
        $health=Invoke-AcceptanceDocker -Arguments @('inspect','--format','{{.State.Health.Status}}',$container)
        Check 'preflight.container-healthy' ($health -eq 'healthy')
    }
    $internal=Get-AcceptanceSecret 'ACCEPTANCE_INTERNAL_SERVICE_TOKEN'
    $jwtSecret=Get-AcceptanceSecret 'ACCEPTANCE_AUTH_JWT_SECRET' -MinimumLength 32
    $paymentSecret=Get-AcceptanceSecret 'ACCEPTANCE_PAYMENT_CALLBACK_SECRET'
    Check 'preflight.database-ready' ((Read-Fact "SELECT COUNT(*) FROM acceptance_control.bootstrap WHERE id=1 AND status='READY';") -eq '1')
    # F07 cannot begin a new fault window while this lock is held.
    $runLock=[IO.File]::Open((Join-Path $context.Runtime 'outbox-closure.lock'),'OpenOrCreate','ReadWrite','None')
    $closure=Get-Content (Join-Path $context.Runtime 'outbox-closure-dead-letter.json') -Raw | ConvertFrom-Json
    Check 'preflight.F07-completed' ($closure.project -eq $ProjectName -and $closure.mysqlContainer -eq $mysql -and $closure.phase -eq 'Completed')
    Assert-ProxiesPass
    $trafficAllowed=$true

    $password=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $auth=Http 'register-owner' 18080 POST '/api/auth/register' 201 '' @{username=('security_'+$runId.Substring(0,18)+'_a');password=$password}
    $tokenA=[string]$auth.token; $userA=[string]$auth.userId
    Assert-Id $userA
    $users.Add(@{role='owner';userId=$userA})
    $auth=Http 'register-other' 18080 POST '/api/auth/register' 201 '' @{username=('security_'+$runId.Substring(0,18)+'_b');password=$password}
    $tokenB=[string]$auth.token; $userB=[string]$auth.userId
    Assert-Id $userB
    $users.Add(@{role='other';userId=$userB})
    $password=$null; $auth=$null

    $pieces=$tokenA -split '[.]'
    $first=if($pieces[2][0] -eq 'A'){'B'}else{'A'}
    $tampered="$($pieces[0]).$($pieces[1]).$first$($pieces[2].Substring(1))"
    $expired=New-ExpiredJwt $tokenA $jwtSecret
    foreach($port in @(18080,18084)){
        foreach($variant in @(@('tampered',$tampered),@('expired',$expired))){
            $rejected=Http "N01.$($variant[0])-jwt-port-$port" $port GET '/api/cart' 401 $variant[1]
            Check "N01.$($variant[0])-jwt-port-$port-code" ($rejected.code -eq 'TOKEN_INVALID')
        }
    }
    $tampered=$null; $expired=$null
    $paid=New-Order 'paid-callback' $userA
    $unpaid=New-Order 'cross-trade-rejected' $userA
    $spoof=@{'X-User-Id'=$userA;'X-Username'='synthetic-owner';'X-Internal-Service-Token'='invalid-test-token'}

    $null=Http 'N02.anonymous-forged-user-denied' 18080 GET "/api/orders/$($paid.orderId)" 401 '' $null $spoof
    $me=Http 'N02.commerce-uses-bearer-not-user-header' 18084 GET '/api/auth/me' 200 $tokenB $null $spoof
    Check 'N02.me-remains-other-user' ([string]$me.userId -ceq $userB)
    $null=Http 'N02.direct-commerce-header-without-jwt-denied' 18084 GET '/api/cart' 401 '' $null $spoof
    $null=Http 'N02.forged-owner-cannot-read' 18080 GET "/api/orders/$($paid.orderId)" 404 $tokenB $null $spoof
    $null=Http 'N02.forged-owner-cannot-pay' 18080 POST "/api/payments/orders/$($paid.orderId)/mock-success" 404 $tokenB $null $spoof
    $null=Http 'N02.forged-owner-cannot-cancel' 18080 POST "/api/orders/$($paid.orderId)/cancel" 404 $tokenB $null $spoof
    foreach($headers in @(@{'X-User-Id'=$userA},$spoof)){
        $kind=if($headers.ContainsKey('X-Internal-Service-Token')){'wrong'}else{'missing'}
        $null=Http "N02.direct-order-$kind-internal-token" 18081 GET "/api/orders/$($paid.orderId)" 401 $tokenA $null $headers
        $null=Http "N02.direct-order-internal-$kind-token" 18081 GET "/internal/orders/$($paid.orderId)/payment-view" 401 '' $null $headers
        $null=Http "N02.direct-inventory-$kind-token" 18082 GET '/internal/inventory/query/1003' 401 '' $null $headers
        $null=Http "N02.direct-payment-$kind-token" 18083 POST "/api/payments/orders/$($paid.orderId)/mock-success" 401 $tokenA $null $headers
    }
    $beforePay=Order-Fact $paid.orderId $userA
    Check 'N02.negative-calls-did-not-change-order' ($beforePay.status -eq 1 -and $beforePay.events -eq 0 -and $beforePay.lockedUnits -eq 1)

    Assert-ProxiesPass
    $trade="SEC-$runId"
    $body=@{orderId=$paid.orderId;outTradeNo=$trade}
    $timestamp=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $wrong=New-CallbackHeaders $paid.orderId $trade $timestamp
    $wrong['X-Payment-Signature']='0'*64
    $null=Http 'N08.wrong-hmac-denied' 18080 POST '/api/payments/callbacks/success' 401 '' $body $wrong
    $expiredHeaders=New-CallbackHeaders $paid.orderId $trade ($timestamp-3600)
    $null=Http 'N08.expired-valid-hmac-denied' 18080 POST '/api/payments/callbacks/success' 401 '' $body $expiredHeaders
    $unchanged=Order-Fact $paid.orderId $userA
    Check 'N08.invalid-callbacks-create-no-payment-event' ($unchanged.status -eq 1 -and $unchanged.events -eq 0 -and $null -eq $unchanged.tradeNo -and $unchanged.lockedUnits -eq 1)
    foreach($attempt in @(1,2)){
        Assert-ProxiesPass
        $headers=New-CallbackHeaders $paid.orderId $trade ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
        $accepted=Http "N08.valid-callback-$attempt" 18080 POST '/api/payments/callbacks/success' 200 '' $body $headers
        Check "N08.valid-callback-$attempt-accepted" ($accepted.status -eq 'ACCEPTED' -and $accepted.orderId -is [string] -and $accepted.orderId -ceq $paid.orderId)
    }
    $alternateTrade="$trade-other"
    $headers=New-CallbackHeaders $paid.orderId $alternateTrade ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
    $rejected=Http 'N08.same-order-different-trade-conflict' 18080 POST '/api/payments/callbacks/success' 409 '' @{orderId=$paid.orderId;outTradeNo=$alternateTrade} $headers
    Check 'N08.same-order-different-trade-rejected' ($rejected.status -eq 'REJECTED')
    $headers=New-CallbackHeaders $unpaid.orderId $trade ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
    $rejected=Http 'N08.same-trade-different-order-conflict' 18080 POST '/api/payments/callbacks/success' 409 '' @{orderId=$unpaid.orderId;outTradeNo=$trade} $headers
    Check 'N08.same-trade-different-order-rejected' ($rejected.status -eq 'REJECTED')
    $stillPending=Order-Fact $unpaid.orderId $userA
    Check 'N08.conflicting-order-has-no-payment' ($stillPending.status -eq 1 -and $null -eq $stillPending.tradeNo -and $stillPending.events -eq 0 -and $stillPending.lockedUnits -eq 1)
    $stage='N08.final-paid-outbox-and-confirmation'
    Wait-Settled $paid $true
    Check 'N08.paid-trade-unchanged-one-event-one-confirmed-unit' ($paid.final.tradeNo -ceq $trade)
    $cancel=Http 'N08.cancel-unpaid-fixture' 18080 POST "/api/orders/$($unpaid.orderId)/cancel" 200 $tokenA
    Check 'N08.cancel-accepted' ($cancel.state -eq 'CANCELED')
    $stage='N08.final-unpaid-cancellation-and-release'
    Wait-Settled $unpaid $false
    Check 'N08.unpaid-order-compensated-without-payment-event' $true
    $other=Http 'N02.other-account-remains-without-orders' 18080 GET '/api/orders?page=0&size=10' 200 $tokenB
    Check 'N02.other-account-order-count-zero' ($other.total -eq 0)
    $passed=$true
}catch{
    # Exception objects can embed headers/JWTs/SQL. Never serialize or echo them.
    $passed=$false
}finally{
    $failedStage=if($passed){$null}else{$stage}
    if($trafficAllowed -and $tokenA){
        foreach($fixture in $fixtures){
            try{
                $fact=Order-Fact $fixture.orderId $fixture.userId
                if($fact.status -eq 1){
                    $null=Http 'cleanup.cancel-unpaid-owned-fixture' 18080 POST "/api/orders/$($fixture.orderId)/cancel" 200 $tokenA
                    Wait-Settled $fixture $false
                    $cleanup.Add(@{orderId=$fixture.orderId;result='canceled-and-released'})
                }elseif($fact.status -eq 2){
                    $cleanup.Add(@{orderId=$fixture.orderId;result='paid-preserved'})
                }else{$cleanup.Add(@{orderId=$fixture.orderId;result='already-terminal'})}
            }catch{
                $passed=$false
                if(-not $failedStage){$failedStage='cleanup.unpaid-fixture'}
                $cleanup.Add(@{orderId=$fixture.orderId;result='needs-inspection'})
            }
        }
    }
    if($runLock){$runLock.Dispose()}
    $internal=$null; $jwtSecret=$null; $paymentSecret=$null; $tokenA=$null; $tokenB=$null
    $tampered=$null; $expired=$null; $auth=$null; $password=$null
}
$report=[ordered]@{
    runId=$runId;project=$ProjectName;startedAtUtc=$started.ToString('o');finishedAtUtc=[datetime]::UtcNow.ToString('o')
    passed=$passed;stoppedAt=$failedStage;users=$users.ToArray();orders=$fixtures.ToArray()
    assertions=$checks.ToArray();cleanup=$cleanup.ToArray()
    scope='N01/N02/N08 real HTTP plus read-only fixture SQL; local single-instance evidence, not a security audit.'
    retainedData='Synthetic users/orders remain. Success pays one SKU 1003 unit and cancels/releases the other.'
}
if($EvidencePath){
    try{Save-AcceptanceJson ([IO.Path]::GetFullPath($EvidencePath)) $report}
    catch{$passed=$false;$report.passed=$false;$report.stoppedAt='evidence.save'}
}
$report | ConvertTo-Json -Depth 8
if(-not $passed){exit 1}
