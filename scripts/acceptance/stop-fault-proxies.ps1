#requires -Version 7.4
param([string]$ProjectName = 'fulfillment-acceptance')
. (Join-Path $PSScriptRoot 'common.ps1')
$context = Get-AcceptanceContext $ProjectName
$registryPath = Join-Path $context.Runtime 'fault-proxies.json'
if (-not (Test-Path -LiteralPath $registryPath)) { return }
$proxyLock = [IO.File]::Open((Join-Path $context.Runtime 'fault-proxies.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
try {
$registry = Get-Content $registryPath -Raw | ConvertFrom-Json
if ($registry.project -ne $ProjectName) { throw 'Fault proxy registry ownership mismatch.' }
foreach ($record in $registry.processes) {
    $owned = Get-AcceptanceOwnedProcess $record
    if ($owned) { Stop-Process -Id $owned.Id -ErrorAction Stop }
}
Save-AcceptanceJson $registryPath @{project=$ProjectName;processes=@()}
Write-Output 'Only registered, identity-verified acceptance fault proxies were stopped.'
} finally { $proxyLock.Dispose() }
