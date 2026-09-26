#requires -Version 7.4
param([string]$ProjectName = 'fulfillment-acceptance')
. (Join-Path $PSScriptRoot 'common.ps1')
$context = Get-AcceptanceContext $ProjectName
$secret = Get-AcceptanceSecret 'ACCEPTANCE_INTERNAL_SERVICE_TOKEN'
$node = (Get-Command node -CommandType Application | Select-Object -First 1).Source
$registryPath = Join-Path $context.Runtime 'fault-proxies.json'
$proxyLock = [IO.File]::Open((Join-Path $context.Runtime 'fault-proxies.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
try {
$records = @()
if (Test-Path -LiteralPath $registryPath) {
    $registry = Get-Content $registryPath -Raw | ConvertFrom-Json
    if ($registry.project -ne $ProjectName) { throw 'Fault proxy registry ownership mismatch.' }
    $records = @($registry.processes)
}
foreach ($spec in @(@{port=18881;upstream=18081},@{port=18882;upstream=18082})) {
    $record = $records | Where-Object port -eq $spec.port | Select-Object -First 1
    $process = if ($record) { Get-AcceptanceOwnedProcess $record } else { $null }
    if ($process) { continue }
    if (Get-NetTCPConnection -LocalPort $spec.port -State Listen -ErrorAction SilentlyContinue) { throw 'Fault port occupied; nothing stopped.' }
    $log = Join-Path $context.Runtime "fault-$($spec.port)-$([Guid]::NewGuid().ToString('N')).log"
    $envMap = @{FAULT_PROXY_PORT="$($spec.port)";FAULT_PROXY_UPSTREAM="http://127.0.0.1:$($spec.upstream)";FAULT_PROXY_TOKEN=$secret;NODE_OPTIONS=$null}
    $process = Start-Process -FilePath $node -ArgumentList ('"' + (Join-Path $PSScriptRoot 'fault-proxy.mjs') + '"') `
        -WindowStyle Hidden -Environment $envMap -RedirectStandardOutput $log -RedirectStandardError ($log + '.err') -PassThru
    $record = [pscustomobject]@{processId=$process.Id;startedAtUtc=$process.StartTime.ToUniversalTime().ToString('o');javaPath=$node;port=$spec.port}
    $records = @($records | Where-Object port -ne $spec.port) + @($record)
    Save-AcceptanceJson $registryPath @{project=$ProjectName;processes=$records}
}
foreach ($record in $records) {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    $ready = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not (Get-AcceptanceOwnedProcess $record)) { throw 'Owned fault proxy exited; inspect local logs.' }
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$($record.port)/__fault__/health" -NoProxy -TimeoutSec 2
            if ($health.status -eq 'UP') { $ready = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 200
    }
    if (-not $ready) { throw 'Fault proxy did not become healthy.' }
    $state = Invoke-RestMethod "http://127.0.0.1:$($record.port)/__fault__/state" -NoProxy -TimeoutSec 2 -Headers @{'X-Fault-Token'=$secret}
    if ($state.mode -ne 'pass' -or $state.remaining -ne 0 -or $state.held -ne 0) {
        throw 'Existing proxy has an active fault; explicitly finish/reset that test before reuse. Held traffic was not released.'
    }
}
Write-Output 'Loopback fault proxies started in pass-through mode. Application URLs are unchanged until explicitly configured.'
} finally { $proxyLock.Dispose() }
