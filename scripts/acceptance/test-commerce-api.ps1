#requires -Version 7.4
<#
Gateway-only smoke/regression checks against the isolated local acceptance deployment.
Creates two synthetic users and two orders: one paid, one canceled. Test records remain
for database verification; successful execution consumes one unit of the selected SKU.
No credentials, authorization headers, or raw response bodies are written to evidence.
#>
param(
    [string]$GatewayUrl = 'http://127.0.0.1:18080',
    [string]$EvidencePath
)

. (Join-Path $PSScriptRoot 'common.ps1')

$gatewayUri = $null
if (-not [Uri]::TryCreate($GatewayUrl, [UriKind]::Absolute, [ref]$gatewayUri) -or
    $gatewayUri.Scheme -notin @('http', 'https') -or -not $gatewayUri.IsLoopback -or
    $gatewayUri.UserInfo -or $gatewayUri.Query -or $gatewayUri.Fragment -or
    $gatewayUri.AbsolutePath -ne '/') {
    throw 'GatewayUrl must be a loopback HTTP(S) origin without credentials, path, query, or fragment.'
}
$gatewayOrigin = $gatewayUri.GetLeftPart([UriPartial]::Authority)
$runId = [Guid]::NewGuid().ToString('N')
$assertions = [Collections.Generic.List[object]]::new()
$orders = [Collections.Generic.List[object]]::new()
$testUsers = [Collections.Generic.List[object]]::new()
$script:CurrentStage = 'initialization'

function Assert-ApiCase {
    param([string]$Name, [bool]$Condition, [Nullable[int]]$HttpStatus)
    [void]$assertions.Add([pscustomobject]@{
        name = $Name
        passed = $Condition
        httpStatus = $HttpStatus
    })
    if (-not $Condition) { throw 'An API acceptance assertion failed.' }
}

function Invoke-GatewayCheck {
    param(
        [string]$Case,
        [ValidateSet('GET', 'POST', 'PUT', 'DELETE')][string]$Method,
        [string]$Path,
        [int]$ExpectedStatus,
        [string]$Token,
        [object]$Body,
        [hashtable]$Headers = @{}
    )
    $script:CurrentStage = $Case
    $requestHeaders = @{ Accept = 'application/json' }
    foreach ($key in $Headers.Keys) { $requestHeaders[$key] = $Headers[$key] }
    if ($Token) { $requestHeaders.Authorization = "Bearer $Token" }
    $arguments = @{
        Uri = "$gatewayOrigin$Path"
        Method = $Method
        Headers = $requestHeaders
        SkipHttpErrorCheck = $true
        MaximumRedirection = 0
        TimeoutSec = 15
        Verbose = $false
        Debug = $false
    }
    if ($null -ne $Body) {
        $arguments.ContentType = 'application/json; charset=utf-8'
        $arguments.Body = ConvertTo-Json -InputObject $Body -Depth 8 -Compress
    }
    try {
        $response = Invoke-WebRequest @arguments
    } catch {
        # A native HTTP exception may contain request/response material: do not expose it.
        throw 'Gateway transport failed; no request or response content was printed.'
    }
    Assert-ApiCase -Name "$Case.http" -Condition ([int]$response.StatusCode -eq $ExpectedStatus) `
        -HttpStatus ([int]$response.StatusCode)
    if ([string]::IsNullOrWhiteSpace($response.Content)) { return @{} }
    try {
        return ConvertFrom-Json -InputObject $response.Content -AsHashtable -ErrorAction Stop
    } catch {
        throw 'Gateway returned an unreadable JSON response; its content was not printed.'
    }
}

function Assert-OrderId {
    param([string]$Case, [object]$Value)
    Assert-ApiCase -Name "$Case.orderId-is-exact-string" `
        -Condition ($Value -is [string] -and $Value -match '^[1-9][0-9]*$')
}

$startedAt = [DateTime]::UtcNow
$passed = $false
$selectedSkuId = $null
try {
    # Each run is independent. Passwords and tokens remain in process memory only.
    $accountPrefix = 'acc_' + $runId.Substring(0, 20)
    $passwordA = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $passwordB = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $authA = Invoke-GatewayCheck -Case 'register-a' -Method POST -Path '/api/auth/register' `
        -ExpectedStatus 201 -Body @{ username = "${accountPrefix}_a"; password = $passwordA }
    $authB = Invoke-GatewayCheck -Case 'register-b' -Method POST -Path '/api/auth/register' `
        -ExpectedStatus 201 -Body @{ username = "${accountPrefix}_b"; password = $passwordB }
    Assert-ApiCase -Name 'registration.tokens-present' `
        -Condition (-not [string]::IsNullOrWhiteSpace($authA['token']) -and
                    -not [string]::IsNullOrWhiteSpace($authB['token']))
    $tokenA = [string]$authA['token']
    $tokenB = [string]$authB['token']
    [void]$testUsers.Add([pscustomobject]@{ role = 'owner'; userId = [string]$authA['userId'] })
    [void]$testUsers.Add([pscustomobject]@{ role = 'other'; userId = [string]$authB['userId'] })

    $authA = Invoke-GatewayCheck -Case 'login-a' -Method POST -Path '/api/auth/login' `
        -ExpectedStatus 200 -Body @{ username = "${accountPrefix}_a"; password = $passwordA }
    Assert-ApiCase -Name 'login.token-present' -Condition (-not [string]::IsNullOrWhiteSpace($authA['token']))
    $tokenA = [string]$authA['token']
    $passwordA = $null
    $passwordB = $null
    $authA = $null
    $authB = $null

    $null = Invoke-GatewayCheck -Case 'anonymous-cart-denied' -Method GET -Path '/api/cart' -ExpectedStatus 401
    foreach ($legacyPath in @('/api/orders', '/api/orders/redis')) {
        # An invalid body prevents accidental creation even if an old deployment lacks the gate.
        $blocked = Invoke-GatewayCheck -Case "legacy-disabled:$legacyPath" -Method POST -Path $legacyPath `
            -Token $tokenA -Body @{} -ExpectedStatus 403
        Assert-ApiCase -Name "legacy-disabled:$legacyPath.code" `
            -Condition ($blocked['code'] -eq 'LEGACY_ORDER_ENTRY_DISABLED')
    }

    $catalog = Invoke-GatewayCheck -Case 'anonymous-products' -Method GET `
        -Path '/api/products?page=1&size=50' -ExpectedStatus 200
    Assert-ApiCase -Name 'catalog.has-products' -Condition (@($catalog['items']).Count -gt 0)
    foreach ($product in $catalog['items']) {
        $detail = Invoke-GatewayCheck -Case "product-detail:$($product['spuId'])" -Method GET `
            -Path "/api/products/$($product['spuId'])" -ExpectedStatus 200
        $eligible = @($detail['skus'] | Where-Object {
            $_['onSale'] -eq $true -and $null -ne $_['availableStock'] -and [int]$_['availableStock'] -ge 2
        })
        if ($eligible.Count -gt 0) {
            $selectedSkuId = $eligible[0]['skuId']
            break
        }
    }
    Assert-ApiCase -Name 'catalog.has-listed-sku-with-two-units' -Condition ($null -ne $selectedSkuId)

    $cart = Invoke-GatewayCheck -Case 'add-payment-item' -Method POST -Path '/api/cart/items' `
        -Token $tokenA -Body @{ skuId = $selectedSkuId; quantity = 1 } -ExpectedStatus 200
    Assert-ApiCase -Name 'cart.one-selected-line' `
        -Condition (@($cart['items']).Count -eq 1 -and [int]$cart['selectedCount'] -eq 1)
    $payAmount = [decimal]$cart['selectedAmount']
    $mismatch = Invoke-GatewayCheck -Case 'reject-amount-mismatch' -Method POST -Path '/api/checkout' `
        -Token $tokenA -Headers @{ 'Idempotency-Key' = "$runId-price-mismatch" } `
        -Body @{ expectedAmount = $payAmount + [decimal]0.01 } -ExpectedStatus 409
    Assert-ApiCase -Name 'amount-mismatch.code' -Condition ($mismatch['code'] -eq 'PRICE_CHANGED')
    $before = Invoke-GatewayCheck -Case 'no-order-after-price-rejection' -Method GET `
        -Path '/api/orders?page=0&size=10' -Token $tokenA -ExpectedStatus 200
    Assert-ApiCase -Name 'price-rejection.created-no-order' -Condition ([long]$before['total'] -eq 0)

    $payKey = "$runId-payment"
    $checkout = Invoke-GatewayCheck -Case 'checkout-payment' -Method POST -Path '/api/checkout' `
        -Token $tokenA -Headers @{ 'Idempotency-Key' = $payKey } `
        -Body @{ expectedAmount = $payAmount } -ExpectedStatus 200
    Assert-OrderId -Case 'checkout-payment' -Value $checkout['orderId']
    $paidOrderId = [string]$checkout['orderId']
    [void]$orders.Add([pscustomobject]@{ branch = 'payment'; orderId = $paidOrderId; totalAmount = $payAmount })
    Assert-ApiCase -Name 'checkout-payment.reserved' `
        -Condition ($checkout['state'] -eq 'RESERVED' -and [decimal]$checkout['totalAmount'] -eq $payAmount)

    $replay = Invoke-GatewayCheck -Case 'checkout-replay-after-cart-clear' -Method POST -Path '/api/checkout' `
        -Token $tokenA -Headers @{ 'Idempotency-Key' = $payKey } `
        -Body @{ expectedAmount = $payAmount } -ExpectedStatus 200
    Assert-OrderId -Case 'checkout-replay' -Value $replay['orderId']
    Assert-ApiCase -Name 'checkout-replay.same-order' `
        -Condition ($replay['replayed'] -eq $true -and $replay['orderId'] -ceq $paidOrderId)
    $conflict = Invoke-GatewayCheck -Case 'checkout-key-reused-with-other-amount' -Method POST -Path '/api/checkout' `
        -Token $tokenA -Headers @{ 'Idempotency-Key' = $payKey } `
        -Body @{ expectedAmount = $payAmount + [decimal]0.01 } -ExpectedStatus 409
    Assert-ApiCase -Name 'checkout-key-reuse.conflict' -Condition ($conflict['code'] -eq 'IDEMPOTENCY_KEY_REUSED')
    $order = Invoke-GatewayCheck -Case 'payment-order-detail' -Method GET `
        -Path "/api/orders/$paidOrderId" -Token $tokenA -ExpectedStatus 200
    Assert-OrderId -Case 'payment-order-detail' -Value $order['orderId']
    Assert-ApiCase -Name 'payment-order.pending-and-snapshot' `
        -Condition ($order['orderId'] -ceq $paidOrderId -and
                    $order['status'] -eq 'PENDING_PAYMENT' -and $order['reservationStatus'] -eq 'RESERVED' -and
                    [decimal]$order['totalAmount'] -eq $payAmount -and @($order['items']).Count -eq 1 -and
                    -not [string]::IsNullOrWhiteSpace($order['items'][0]['nameSnapshot']))

    foreach ($attempt in 1..2) {
        $payment = Invoke-GatewayCheck -Case "mock-payment-$attempt" -Method POST `
            -Path "/api/payments/orders/$paidOrderId/mock-success" -Token $tokenA -ExpectedStatus 200
        Assert-OrderId -Case "mock-payment-$attempt" -Value $payment['orderId']
        Assert-ApiCase -Name "mock-payment-$attempt.accepted" `
            -Condition ($payment['status'] -eq 'ACCEPTED' -and $payment['orderId'] -ceq $paidOrderId)
    }
    $order = Invoke-GatewayCheck -Case 'paid-order-detail' -Method GET `
        -Path "/api/orders/$paidOrderId" -Token $tokenA -ExpectedStatus 200
    Assert-ApiCase -Name 'paid-order.final-state' `
        -Condition ($order['status'] -eq 'PAID' -and $order['outTradeNo'] -ceq "MOCK-$paidOrderId")
    $null = Invoke-GatewayCheck -Case 'paid-order-cannot-cancel' -Method POST `
        -Path "/api/orders/$paidOrderId/cancel" -Token $tokenA -ExpectedStatus 409

    $null = Invoke-GatewayCheck -Case 'other-user-cannot-read' -Method GET `
        -Path "/api/orders/$paidOrderId" -Token $tokenB -ExpectedStatus 404
    $null = Invoke-GatewayCheck -Case 'other-user-cannot-pay' -Method POST `
        -Path "/api/payments/orders/$paidOrderId/mock-success" -Token $tokenB -ExpectedStatus 404
    $null = Invoke-GatewayCheck -Case 'other-user-cannot-cancel' -Method POST `
        -Path "/api/orders/$paidOrderId/cancel" -Token $tokenB -ExpectedStatus 404

    $cart = Invoke-GatewayCheck -Case 'add-cancel-item' -Method POST -Path '/api/cart/items' `
        -Token $tokenA -Body @{ skuId = $selectedSkuId; quantity = 1 } -ExpectedStatus 200
    $cancelAmount = [decimal]$cart['selectedAmount']
    $checkout = Invoke-GatewayCheck -Case 'checkout-cancel' -Method POST -Path '/api/checkout' `
        -Token $tokenA -Headers @{ 'Idempotency-Key' = "$runId-cancel" } `
        -Body @{ expectedAmount = $cancelAmount } -ExpectedStatus 200
    Assert-OrderId -Case 'checkout-cancel' -Value $checkout['orderId']
    $canceledOrderId = [string]$checkout['orderId']
    [void]$orders.Add([pscustomobject]@{ branch = 'cancel'; orderId = $canceledOrderId; totalAmount = $cancelAmount })
    Assert-ApiCase -Name 'checkout-cancel.reserved-and-independent' `
        -Condition ($checkout['state'] -eq 'RESERVED' -and $canceledOrderId -cne $paidOrderId)
    foreach ($attempt in 1..2) {
        $cancel = Invoke-GatewayCheck -Case "cancel-$attempt" -Method POST `
            -Path "/api/orders/$canceledOrderId/cancel" -Token $tokenA -ExpectedStatus 200
        Assert-OrderId -Case "cancel-$attempt" -Value $cancel['orderId']
        Assert-ApiCase -Name "cancel-$attempt.canceled" `
            -Condition ($cancel['state'] -eq 'CANCELED' -and $cancel['orderId'] -ceq $canceledOrderId)
    }
    $settled = $false
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        $order = Invoke-GatewayCheck -Case "cancel-settlement-$attempt" -Method GET `
            -Path "/api/orders/$canceledOrderId" -Token $tokenA -ExpectedStatus 200
        if ($order['status'] -eq 'CANCELED' -and $order['reservationStatus'] -eq 'COMPENSATED') {
            $settled = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    Assert-ApiCase -Name 'cancel.finally-compensated' -Condition $settled
    $null = Invoke-GatewayCheck -Case 'canceled-order-cannot-pay' -Method POST `
        -Path "/api/payments/orders/$canceledOrderId/mock-success" -Token $tokenA -ExpectedStatus 409

    $mine = Invoke-GatewayCheck -Case 'owner-order-list' -Method GET `
        -Path '/api/orders?page=0&size=10' -Token $tokenA -ExpectedStatus 200
    Assert-ApiCase -Name 'owner-list.exactly-two-orders' -Condition ([long]$mine['total'] -eq 2)
    $listedIds = @($mine['orders'] | ForEach-Object {
        Assert-OrderId -Case 'owner-list-item' -Value $_['orderId']
        [string]$_['orderId']
    })
    Assert-ApiCase -Name 'owner-list.exact-ids' `
        -Condition ($listedIds -ccontains $paidOrderId -and $listedIds -ccontains $canceledOrderId)
    $paidRows = @($mine['orders'] | Where-Object { $_['orderId'] -ceq $paidOrderId -and $_['status'] -eq 'PAID' })
    $canceledRows = @($mine['orders'] | Where-Object {
        $_['orderId'] -ceq $canceledOrderId -and $_['status'] -eq 'CANCELED' -and
        $_['reservationStatus'] -eq 'COMPENSATED'
    })
    Assert-ApiCase -Name 'owner-list.states-unchanged-by-negative-calls' `
        -Condition ($paidRows.Count -eq 1 -and $canceledRows.Count -eq 1)
    $other = Invoke-GatewayCheck -Case 'other-user-order-list' -Method GET `
        -Path '/api/orders?page=0&size=10' -Token $tokenB -ExpectedStatus 200
    Assert-ApiCase -Name 'other-user-list.empty' -Condition ([long]$other['total'] -eq 0)
    $cart = Invoke-GatewayCheck -Case 'cart-cleared-after-checkout' -Method GET `
        -Path '/api/cart' -Token $tokenA -ExpectedStatus 200
    Assert-ApiCase -Name 'cart.empty' -Condition (@($cart['items']).Count -eq 0)
    $passed = $true
} catch {
    # All report values below are an explicit allowlist, never exception/request objects.
    $passed = $false
} finally {
    $tokenA = $null
    $tokenB = $null
    $passwordA = $null
    $passwordB = $null
    $authA = $null
    $authB = $null
}

$report = [ordered]@{
    runId = $runId
    startedAtUtc = $startedAt.ToString('o')
    finishedAtUtc = [DateTime]::UtcNow.ToString('o')
    passed = $passed
    stoppedAt = if ($passed) { $null } else { $script:CurrentStage }
    failureNote = if ($passed) { $null } else { 'Check the failed assertion or last stage; raw errors and responses are intentionally omitted.' }
    selectedSkuId = $selectedSkuId
    users = @($testUsers.ToArray())
    orders = @($orders.ToArray())
    assertions = @($assertions.ToArray())
    scope = 'Gateway HTTP only; inventory/Outbox database facts and browser behavior need separate verification.'
    retainedData = 'Synthetic users and orders remain. A successful run pays for one unit and cancels one unit.'
}
if ($EvidencePath) {
    try {
        $resolvedEvidencePath = [IO.Path]::GetFullPath($EvidencePath)
        Save-AcceptanceJson -Path $resolvedEvidencePath -Value $report
    } catch {
        $report.passed = $false
        $report.failureNote = 'Could not save the sanitized evidence file; results are still printed below.'
        $passed = $false
    }
}
ConvertTo-Json -InputObject $report -Depth 8
if (-not $passed) { exit 1 }
