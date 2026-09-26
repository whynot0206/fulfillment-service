#requires -Version 7.4
[CmdletBinding()]
param(
    [string]$ProjectName = 'fulfillment-acceptance',
    [ValidateSet('inventory-service','order-service','payment-service','commerce-service','gateway')]
    [string[]]$Services = @('gateway','commerce-service','payment-service','order-service','inventory-service')
)
. (Join-Path $PSScriptRoot 'common.ps1')
$context = Get-AcceptanceContext $ProjectName
$registryPath = Join-Path $context.Runtime 'backend-processes.json'
if (-not (Test-Path -LiteralPath $registryPath)) { Write-Output 'No owned backend registry; nothing stopped.'; exit 0 }
$lock = [IO.File]::Open((Join-Path $context.Runtime 'backend.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
try {
    $registry = Get-Content -LiteralPath $registryPath -Raw | ConvertFrom-Json
    if ($registry.project -ne $ProjectName) { throw 'Backend registry project does not match.' }
    foreach ($service in $Services) {
        $record = @($registry.processes | Where-Object { $_.service -eq $service }) | Select-Object -First 1
        if (-not $record) { continue }
        $process = Get-AcceptanceOwnedProcess $record
        if ($process) {
            Stop-Process -InputObject $process -ErrorAction Stop
            if (-not $process.WaitForExit(10000)) { throw "Owned $service process has not exited; no other process will be stopped." }
            Write-Output "Stopped owned $service PID $($process.Id)."
        }
    }
    Write-Output 'Only registry-owned Java processes were considered; containers, data, and other processes were retained.'
} finally { $lock.Dispose() }
