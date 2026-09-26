#requires -Version 7.4
<# Offline contract checks. Parses script text and executes selected pure/mocked functions.
   Never invokes the acceptance script body, HTTP, Docker, SQL, or process lifecycle tools.
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$target = Join-Path $PSScriptRoot 'test-outbox-closure.ps1'
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($target, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Closure script has PowerShell syntax errors.' }
$source = Get-Content -LiteralPath $target -Raw
$count = 0
function Check([string]$Name, [bool]$Ok) {
    if (-not $Ok) { throw "Offline closure contract failed: $Name" }
    $script:count++
}
function Load-Function([string]$Name) {
    $definition = @($ast.FindAll({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $Name
    }, $true))
    if ($definition.Count -ne 1) { throw "Expected one function $Name." }
    return [scriptblock]::Create($definition[0].Extent.Text)
}
# Dot-source only individually selected function definitions, not their containing script.
. (Load-Function 'Same-Stock')
. (Load-Function 'Event-Fact')
. (Load-Function 'Assert-Closure')
. (Load-Function 'Start-DeadLetter')
$checks = [Collections.Generic.List[object]]::new()
$observations = [Collections.Generic.List[object]]::new()
$script:queryCount = 0
function Read-Fact([string]$Sql) {
    $script:queryCount++
    Check 'read-only SQL' ($Sql -match '^SELECT\b' -and $Sql -notmatch '(?i)\b(UPDATE|INSERT|DELETE|CALL|ALTER|TRUNCATE)\b')
    return '{"eventId":"9","status":0,"retryCount":1,"redriveCount":0,"delaySeconds":2,"updatedEpoch":100,"leaseOwner":null,"leaseUntilEpoch":null,"databaseEpoch":100}'
}
Check 'stock equality compares both fields' (Same-Stock @{stock=4;locked=2} @{stock=4;locked=2})
Check 'stock mismatch rejected' (-not (Same-Stock @{stock=4;locked=2} @{stock=3;locked=2}))
Check 'locked mismatch rejected' (-not (Same-Stock @{stock=4;locked=2} @{stock=4;locked=1}))
$sample = Event-Fact '900000000000000001'
Check 'event parsed without losing ID' ($sample.eventId -ceq '9' -and $sample.delaySeconds -eq 2)
$before = $queryCount
$rejected = $false
try { $null = Event-Fact "1'; UPDATE anything" } catch { $rejected = $true }
Check 'unsafe recorded ID rejected before SQL' ($rejected -and $queryCount -eq $before)

# Replace every external dependency used by Start-DeadLetter before exercising its flow.
$statePath = 'offline-state-placeholder'
$ProjectName = 'fulfillment-acceptance-offline'
$mysql = 'offline-owned-container'
$state = $null
$preserveFault = $false
$script:saves = 0
$script:injected = $null
function Test-Path { return $false }
function Proxy-State { return @{mode='pass';held=0;hits=7} }
function Read-Fact([string]$Sql) {
    if ($Sql -match 'sku_stock_lock') { return '1|1|1' }
    return '0'
}
function New-Fixture { return @{orderId='900000000000000001';skuId=1005;before=@{stock=5;locked=0};reserved=@{stock=4;locked=1}} }
function Api { return @{status=200} }
function Set-Fault([string]$Mode, [string]$Path, [int]$Remaining) { $script:injected=@($Mode,$Path,$Remaining) }
function Event-Fact { return @{eventId='9';status=0;retryCount=1;delaySeconds=2} }
function Save-State { $script:saves++ }
$null = Start-DeadLetter
Check 'starter uses real confirm rejection window' ($injected[0] -eq 'reject' -and $injected[1] -eq '/internal/inventory/confirm' -and $injected[2] -eq 100)
Check 'starter preserves fault for default retry schedule' $preserveFault
Check 'starter records resumable phase and event' ($state.phase -eq 'WaitingForDeadLetter' -and $state.eventId -ceq '9' -and $state.proxyHitsAtStart -eq 7)
Check 'starter does not persist credentials' (-not ($state.Keys -match 'token|password|secret'))
Check 'starter persists before and after first failure' ($saves -eq 2 -and $observations.Count -eq 1)
Check 'starter asserts first default backoff' (@($checks | Where-Object name -eq 'F07.first-default-retry-observed').Count -eq 1)

Check 'no direct process termination' ($source -notmatch '(?im)^\s*(Stop-Process|taskkill|docker\s)\b')
Check 'crash uses selected owned service helper' ($source.Contains('-ProjectName $ProjectName -Services order-service'))
Check 'ownership is checked before dispatch' ($source.IndexOf("`nAssert-OwnedListeners`n") -lt $source.IndexOf('switch ($Case)'))
Check 'wrong-generation check is before dependency restore and valid redrive' ($source.IndexOf("Expect-RedriveRejected 'F07.wrong-dead-letter-generation-is-rejected'") -lt $source.IndexOf("'F07.dependency-restored-before-maintenance'"))
Check 'real default retry count and timing enforced' ($source.Contains('$event.retryCount -eq 10') -and $source.Contains('$observations[0].updatedEpoch) -ge 766'))
Check 'no timing configuration override' ($source -notmatch '(?i)FULFILLMENT_OUTBOX|SPRING_APPLICATION_JSON|SetEnvironmentVariable')
Check 'lease expiry checked using database clock' ($source.Contains('lease_until <= CURRENT_TIMESTAMP(6)') -and $source.Contains("'F06.claim-used-default-sixty-second-lease'"))
Check 'maintenance goes through existing explicit Execute entrypoint' ($source.Contains("'redrive-outbox.ps1'") -and $source.Contains('-RequestId $requestId -Execute -Confirm:$false'))
Check 'real audit and rejected duplicate assertions exist' ($source.Contains("'F07.audit-retains-original-failure-generation'") -and $source.Contains("'F07.rejected-repeats-do-not-add-audit-or-change-generation'"))
Check 'failed-release proof retained' ($source.Contains("'F04.original-reservation-remains-locked'") -and $source.Contains("'F04.stock-restored-exactly-once'"))
Write-Output "Offline outbox closure contract: $count checks passed; no HTTP, Docker, SQL, or process operations performed."
