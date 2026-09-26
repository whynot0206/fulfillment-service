#requires -Version 7.4
<#
Small correctness smoke test, NOT a load test or capacity measurement.
An isolated synthetic user sends 10 identical checkout requests, then races payment
against cancellation. Test data remains; the winning payment may consume one SKU unit.
#>
param(
    [string]$GatewayUrl = 'http://127.0.0.1:18080',
    [string]$EvidencePath
)

. (Join-Path $PSScriptRoot 'common.ps1')
$gatewayUri = $null
if (-not [Uri]::TryCreate($GatewayUrl, [UriKind]::Absolute, [ref]$gatewayUri) -or
    $gatewayUri.Scheme -notin @('http', 'https') -or -not $gatewayUri.IsLoopback -or
    $gatewayUri.UserInfo -or $gatewayUri.Query -or $gatewayUri.Fragment -or $gatewayUri.AbsolutePath -ne '/') {
    throw 'GatewayUrl must be a loopback HTTP(S) origin without credentials, path, query, or fragment.'
}
$origin = $gatewayUri.GetLeftPart([UriPartial]::Authority)
$runId = [Guid]::NewGuid().ToString('N')
$checks = [Collections.Generic.List[object]]::new()
$checkoutResults = [Collections.Generic.List[object]]::new()
$raceResults = [Collections.Generic.List[object]]::new()
$script:Stage = 'initialization'

function Assert-Smoke {
    param([string]$Name, [bool]$Condition)
    [void]$checks.Add([pscustomobject]@{ name = $Name; passed = $Condition })
    if (-not $Condition) { throw 'Concurrency smoke assertion failed.' }
}

function Invoke-SmokeApi {
    param([string]$Name, [string]$Method, [string]$Path, [int]$Status,
          [string]$Token, [object]$Body)
    $script:Stage = $Name
    $headers = @{ Accept = 'application/json' }
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $arguments = @{
        Uri = "$origin$Path"; Method = $Method; Headers = $headers; SkipHttpErrorCheck = $true
        TimeoutSec = 15; MaximumRedirection = 0; Verbose = $false; Debug = $false
    }
    if ($null -ne $Body) {
        $arguments.ContentType = 'application/json; charset=utf-8'
        $arguments.Body = ConvertTo-Json -InputObject $Body -Depth 6 -Compress
    }
    try { $response = Invoke-WebRequest @arguments }
    catch { throw 'HTTP transport failed; sensitive request and response material was omitted.' }
    Assert-Smoke -Name "$Name.http-$Status" -Condition ([int]$response.StatusCode -eq $Status)
    if ([string]::IsNullOrWhiteSpace($response.Content)) { return @{} }
    try { return ConvertFrom-Json -InputObject $response.Content -AsHashtable -ErrorAction Stop }
    catch { throw 'Response JSON could not be parsed; its content was omitted.' }
}

function Invoke-ParallelPosts {
    param([Net.Http.HttpClient]$Client, [object[]]$Requests)
    $pending = [Collections.Generic.List[Threading.Tasks.Task[Net.Http.HttpResponseMessage]]]::new()
    $messages = [Collections.Generic.List[Net.Http.HttpRequestMessage]]::new()
    $outcomes = [Collections.Generic.List[object]]::new()
    try {
        foreach ($spec in $Requests) {
            $message = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post, [string]$spec.path)
            [void]$messages.Add($message)
            if ($spec.ContainsKey('key')) { [void]$message.Headers.TryAddWithoutValidation('Idempotency-Key', $spec.key) }
            if ($spec.ContainsKey('body')) {
                $json = ConvertTo-Json -InputObject $spec.body -Depth 6 -Compress
                $message.Content = [Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, 'application/json')
            }
            # Start every request before awaiting any response. No separate user/account is used.
            [void]$pending.Add($Client.SendAsync($message))
        }
        for ($index = 0; $index -lt $pending.Count; $index++) {
            $response = $pending[$index].GetAwaiter().GetResult()
            try {
                $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                $body = if ([string]::IsNullOrWhiteSpace($content)) { @{} } else {
                    ConvertFrom-Json -InputObject $content -AsHashtable -ErrorAction Stop
                }
                [void]$outcomes.Add([pscustomobject]@{
                    name = $Requests[$index].name; status = [int]$response.StatusCode; body = $body
                })
            } finally { $response.Dispose() }
        }
        return $outcomes.ToArray()
    } catch {
        throw 'Concurrent HTTP batch failed; raw errors and responses were omitted.'
    } finally {
        foreach ($message in $messages) { $message.Dispose() }
    }
}

$passed = $false
$startedAt = [DateTime]::UtcNow
$client = $null
$userId = $null
$skuId = $null
$orderId = $null
$amount = $null
$finalState = $null
$reservationState = $null
try {
    $password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $auth = Invoke-SmokeApi -Name 'register' -Method POST -Path '/api/auth/register' -Status 201 `
        -Body @{ username = 'race_' + $runId.Substring(0, 20); password = $password }
    Assert-Smoke -Name 'registration.token-present' -Condition (-not [string]::IsNullOrWhiteSpace($auth['token']))
    $token = [string]$auth['token']
    $userId = [string]$auth['userId']
    $password = $null
    $auth = $null

    $catalog = Invoke-SmokeApi -Name 'products' -Method GET -Path '/api/products?page=1&size=50' -Status 200
    foreach ($product in $catalog['items']) {
        $detail = Invoke-SmokeApi -Name 'product-detail' -Method GET -Path "/api/products/$($product['spuId'])" -Status 200
        $eligible = @($detail['skus'] | Where-Object {
            $_['onSale'] -eq $true -and $null -ne $_['availableStock'] -and [int]$_['availableStock'] -ge 1
        })
        if ($eligible.Count -gt 0) { $skuId = $eligible[0]['skuId']; break }
    }
    Assert-Smoke -Name 'catalog.has-stocked-sku' -Condition ($null -ne $skuId)
    $cart = Invoke-SmokeApi -Name 'add-one-item' -Method POST -Path '/api/cart/items' -Status 200 `
        -Token $token -Body @{ skuId = $skuId; quantity = 1 }
    Assert-Smoke -Name 'cart.one-line' -Condition (@($cart['items']).Count -eq 1 -and [int]$cart['selectedCount'] -eq 1)
    $amount = [decimal]$cart['selectedAmount']

    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $client = [Net.Http.HttpClient]::new($handler)
    $client.BaseAddress = [Uri]::new("$origin/")
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    $client.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
    $client.DefaultRequestHeaders.Accept.ParseAdd('application/json')
    $specs = @(1..10 | ForEach-Object {
        @{ name = "checkout-$_"; path = '/api/checkout'; key = "$runId-same-checkout"; body = @{ expectedAmount = $amount } }
    })
    $script:Stage = 'ten-concurrent-checkouts'
    $outcomes = @(Invoke-ParallelPosts -Client $client -Requests $specs)
    foreach ($outcome in $outcomes) {
        [void]$checkoutResults.Add([pscustomobject]@{
            request = $outcome.name; httpStatus = $outcome.status
            state = $outcome.body['state']; code = $outcome.body['code']
            orderId = $outcome.body['orderId']; replayed = $outcome.body['replayed']
        })
        $valid = ($outcome.status -eq 200 -and $outcome.body['state'] -eq 'RESERVED') -or
                 ($outcome.status -eq 409 -and $outcome.body['code'] -eq 'CHECKOUT_IN_PROGRESS')
        Assert-Smoke -Name "$($outcome.name).success-or-in-progress" -Condition $valid
        if ($outcome.status -eq 200) {
            Assert-Smoke -Name "$($outcome.name).exact-string-id" `
                -Condition ($outcome.body['orderId'] -is [string] -and $outcome.body['orderId'] -match '^[1-9][0-9]*$')
        }
    }
    $successes = @($outcomes | Where-Object { $_.status -eq 200 })
    Assert-Smoke -Name 'checkout.at-least-one-success' -Condition ($successes.Count -ge 1)
    $orderId = [string]$successes[0].body['orderId']
    Assert-Smoke -Name 'checkout.every-success-has-the-same-order' `
        -Condition (@($successes | Where-Object { $_.body['orderId'] -cne $orderId }).Count -eq 0)
    $mine = Invoke-SmokeApi -Name 'list-after-concurrent-checkout' -Method GET -Path '/api/orders?page=0&size=10' `
        -Token $token -Status 200
    Assert-Smoke -Name 'checkout.exactly-one-owned-order' `
        -Condition ([long]$mine['total'] -eq 1 -and @($mine['orders']).Count -eq 1 -and
                    $mine['orders'][0]['orderId'] -ceq $orderId)
    Assert-Smoke -Name 'checkout.pending-before-payment-cancel-race' `
        -Condition ($mine['orders'][0]['status'] -eq 'PENDING_PAYMENT' -and
                    $mine['orders'][0]['reservationStatus'] -eq 'RESERVED')

    $script:Stage = 'payment-cancel-race'
    $race = @(Invoke-ParallelPosts -Client $client -Requests @(
        @{ name = 'payment'; path = "/api/payments/orders/$orderId/mock-success" },
        @{ name = 'cancel'; path = "/api/orders/$orderId/cancel" }
    ))
    foreach ($outcome in $race) {
        [void]$raceResults.Add([pscustomobject]@{
            request = $outcome.name; httpStatus = $outcome.status
            state = $outcome.body['state']; paymentStatus = $outcome.body['status']; orderId = $outcome.body['orderId']
        })
    }
    Assert-Smoke -Name 'race.one-success-and-one-conflict' `
        -Condition (@($race | Where-Object { $_.status -eq 200 }).Count -eq 1 -and
                    @($race | Where-Object { $_.status -eq 409 }).Count -eq 1)
    $paymentWon = $race[0].status -eq 200
    Assert-Smoke -Name 'race.response-matches-winner' `
        -Condition (($paymentWon -and $race[0].body['status'] -eq 'ACCEPTED') -or
                    (-not $paymentWon -and $race[1].body['state'] -eq 'CANCELED'))
    foreach ($outcome in $race) {
        Assert-Smoke -Name "race.$($outcome.name).exact-order-id" `
            -Condition ($outcome.body['orderId'] -is [string] -and $outcome.body['orderId'] -ceq $orderId)
    }
    $settled = $false
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        $detail = Invoke-SmokeApi -Name "race-final-order-$attempt" -Method GET -Path "/api/orders/$orderId" `
            -Token $token -Status 200
        $finalState = $detail['status']
        $reservationState = $detail['reservationStatus']
        if (($paymentWon -and $finalState -eq 'PAID' -and $reservationState -eq 'RESERVED') -or
            (-not $paymentWon -and $finalState -eq 'CANCELED' -and $reservationState -eq 'COMPENSATED')) {
            $settled = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    Assert-Smoke -Name 'race.final-order-matches-winning-operation' -Condition $settled
    $passed = $true
} catch {
    # Never copy Exception, request headers, registration responses, or message text to reports.
    $passed = $false
} finally {
    if ($null -ne $client) { $client.Dispose() }
    $token = $null
    $password = $null
    $auth = $null
}

$report = [ordered]@{
    runId = $runId; kind = 'small-concurrency-correctness-smoke-not-load-test'
    startedAtUtc = $startedAt.ToString('o'); finishedAtUtc = [DateTime]::UtcNow.ToString('o')
    passed = $passed; stoppedAt = if ($passed) { $null } else { $script:Stage }
    userId = $userId; skuId = $skuId; orderId = $orderId; totalAmount = $amount
    finalOrderState = $finalState; finalReservationState = $reservationState
    checkoutResponses = @($checkoutResults.ToArray()); raceResponses = @($raceResults.ToArray())
    assertions = @($checks.ToArray())
    scope = 'Gateway-only HTTP; verify inventory, lock records and Outbox independently using this order ID.'
    retainedData = 'One synthetic user and one order remain. Payment winning may consume one unit.'
}
if ($EvidencePath) {
    try { Save-AcceptanceJson -Path ([IO.Path]::GetFullPath($EvidencePath)) -Value $report }
    catch {
        $passed = $false
        $report.passed = $false
        $report['evidenceSaveFailed'] = $true
    }
}
ConvertTo-Json -InputObject $report -Depth 8
if (-not $passed) { exit 1 }
