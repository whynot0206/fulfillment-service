#requires -Version 7.4
[CmdletBinding()]
param()

# Read-only regression test: use only this PowerShell process as the identity fixture.
# No credentials/runtime registry are read, no process is launched or stopped, and
# no Docker, SQL, HTTP or filesystem mutation is performed.
. (Join-Path $PSScriptRoot 'common.ps1')
$self = Get-Process -Id $PID -ErrorAction Stop
$startedUtc = $self.StartTime.ToUniversalTime()

function New-OwnershipTestRecord {
    param([Parameter(Mandatory)]$StartedAt, [string]$ExecutablePath = $self.Path)
    [pscustomobject]@{
        processId = $self.Id
        startedAtUtc = $StartedAt
        javaPath = $ExecutablePath
    }
}

function Assert-OwnershipAccepted {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)]$Record)
    $actual = Get-AcceptanceOwnedProcess $Record
    if ($null -eq $actual -or $actual.Id -ne $self.Id -or $actual.Path -ne $self.Path) {
        throw "$Name failed: the matching current process was not returned."
    }
    [pscustomobject]@{ case = $Name; result = 'PASS' }
}

function Assert-OwnershipRejected {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)]$Record)
    $rejected = $false
    try {
        [void](Get-AcceptanceOwnedProcess $Record)
    } catch {
        if ($_.Exception.Message -ne 'Recorded PID was reused or executable identity changed; refusing to manage that process.') {
            throw "$Name failed with an unexpected validation error."
        }
        $rejected = $true
    }
    if (-not $rejected) { throw "$Name failed: a mismatched identity was accepted." }
    [pscustomobject]@{ case = $Name; result = 'PASS' }
}

$stringRecord = New-OwnershipTestRecord -StartedAt $startedUtc.ToString('o')
$dateRecord = New-OwnershipTestRecord -StartedAt $startedUtc
if ($stringRecord.startedAtUtc -isnot [string] -or $dateRecord.startedAtUtc -isnot [datetime]) {
    throw 'The test fixtures must cover both string and DateTime inputs explicitly.'
}

$results = @(
    Assert-OwnershipAccepted -Name 'ISO UTC string matches' -Record $stringRecord
    Assert-OwnershipAccepted -Name 'UTC DateTime matches' -Record $dateRecord
    Assert-OwnershipAccepted -Name 'Local DateTime for same instant matches' -Record (New-OwnershipTestRecord -StartedAt $startedUtc.ToLocalTime())
    Assert-OwnershipAccepted -Name 'Current PowerShell JSON round trip matches' -Record ($stringRecord | ConvertTo-Json | ConvertFrom-Json)
    Assert-OwnershipRejected -Name 'Wrong string timestamp is rejected' -Record (New-OwnershipTestRecord -StartedAt $startedUtc.AddSeconds(1).ToString('o'))
    Assert-OwnershipRejected -Name 'Wrong DateTime timestamp is rejected' -Record (New-OwnershipTestRecord -StartedAt $startedUtc.AddSeconds(1))
    Assert-OwnershipRejected -Name 'Wrong executable path with string date is rejected' -Record (New-OwnershipTestRecord -StartedAt $startedUtc.ToString('o') -ExecutablePath ($self.Path + '.not-owned'))
    Assert-OwnershipRejected -Name 'Wrong executable path with DateTime is rejected' -Record (New-OwnershipTestRecord -StartedAt $startedUtc -ExecutablePath ($self.Path + '.not-owned'))
)

$results
Write-Output "Process ownership checks: $($results.Count) passed; only the current PowerShell process was inspected."
