#requires -Version 7.4
# Offline synthetic snapshots only; never contacts the acceptance deployment.
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'state-invariants.ps1')
$checks = 0
function Assert-Snapshot([string]$Name, [object[]]$Rows, [bool]$ExpectedValid, [bool]$Settled=$true) {
    $violations = @(Get-AcceptanceStateViolations -Rows $Rows -RequireSettled:$Settled)
    if (($violations.Count -eq 0) -ne $ExpectedValid) { throw "State invariant failed: $Name" }
    $script:checks++
}
function OrderRow([int]$Status, [int]$Reservation, [int]$Locked=0, [int]$Confirmed=0, [int]$Released=0) {
    @{kind='order';orderId='9007199254740993';status=$Status;reservationStatus=$Reservation;
        locked=$Locked;confirmed=$Confirmed;released=$Released;itemCount=3}
}
function StockRow([int]$Available=100, [int]$Locked=0, [int]$Active=0, [int]$Confirmed=0, [int]$Released=0) {
    @{kind='stock';skuId=1002;available=$Available;locked=$Locked;activeLocks=$Active;
        confirmed=$Confirmed;released=$Released}
}
Assert-Snapshot 'canceled compensated without live stock' @((OrderRow 3 3 0 0 3)) $true
Assert-Snapshot 'canceled failed without any reservation' @((OrderRow 3 4)) $true
Assert-Snapshot 'released history does not constitute live stock on failed order' @((OrderRow 3 4 0 0 3)) $true
Assert-Snapshot 'canceled pending compensation is not settled even without locks' @((OrderRow 3 2)) $false
Assert-Snapshot 'canceled reserving is not settled' @((OrderRow 3 0)) $false
Assert-Snapshot 'canceled reserved is not settled' @((OrderRow 3 1)) $false
Assert-Snapshot 'canceled compensated cannot retain active stock' @((OrderRow 3 3 3)) $false
Assert-Snapshot 'canceled failed cannot retain active stock' @((OrderRow 3 4 3)) $false
Assert-Snapshot 'canceled compensated cannot retain confirmed stock' @((OrderRow 3 3 0 3)) $false
Assert-Snapshot 'canceled failed cannot retain confirmed stock' @((OrderRow 3 4 0 3)) $false
Assert-Snapshot 'paid requires all item quantities confirmed' @((OrderRow 2 1 0 3)) $true
Assert-Snapshot 'paid without confirmation is invalid' @((OrderRow 2 1)) $false
Assert-Snapshot 'paid with partial confirmation is invalid' @((OrderRow 2 1 0 2)) $false
Assert-Snapshot 'paid cannot retain active stock' @((OrderRow 2 1 1 3)) $false
Assert-Snapshot 'pending order fails settled audit' @((OrderRow 1 1 3)) $false
Assert-Snapshot 'pending order is allowed in nonsettled audit' @((OrderRow 1 1 3)) $true $false
Assert-Snapshot 'available plus confirmed conserved; released history excluded' @((StockRow 97 0 0 3 12)) $true
Assert-Snapshot 'stock conservation rejects missing units' @((StockRow 99)) $false
Assert-Snapshot 'stock conservation rejects surplus units' @((StockRow 101)) $false
Assert-Snapshot 'negative stock rejected despite conserved total' @((StockRow -1 101 101)) $false $false
Assert-Snapshot 'lock count must match active lock records' @((StockRow 99 1 0)) $false $false
Assert-Snapshot 'active reservation fails settled audit' @((StockRow 97 3 3)) $false
Assert-Snapshot 'active reservation allowed in nonsettled audit' @((StockRow 97 3 3)) $true $false
Assert-Snapshot 'sent outbox is settled' @(@{kind='outbox';orderId='1';status=2}) $true
Assert-Snapshot 'pending outbox is not settled' @(@{kind='outbox';orderId='1';status=0}) $false
Assert-Snapshot 'dead outbox is not settled' @(@{kind='outbox';orderId='1';status=3}) $false
[pscustomobject]@{passed=$true;assertions=$checks;scope='offline synthetic state invariants only'} | ConvertTo-Json
