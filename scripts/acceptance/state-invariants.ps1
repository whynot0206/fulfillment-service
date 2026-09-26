# Pure snapshot checks: no Docker, HTTP, database access, or repair side effects.
function Get-AcceptanceStateViolations {
    [CmdletBinding()]
    param([AllowEmptyCollection()][object[]]$Rows, [switch]$RequireSettled)
    foreach ($row in $Rows) {
        if ($row.kind -eq 'stock') {
            if ($row.available -lt 0 -or $row.locked -lt 0 -or $row.locked -ne $row.activeLocks) {
                "SKU $($row.skuId): negative or mismatched active stock."
            }
            # Dedicated acceptance fixture: the five demo SKUs each start at 100 units.
            # RELEASED rows are history, not remaining or consumed stock.
            if ($row.skuId -in @(1001,1002,1003,1004,1005) -and
                    ($row.available + $row.locked + $row.confirmed) -ne 100) {
                "SKU $($row.skuId): initial stock conservation failed."
            }
            if ($RequireSettled -and $row.locked -ne 0) { "SKU $($row.skuId): reservation still active." }
        }
        if ($row.kind -eq 'order') {
            if ($row.status -eq 2 -and ($row.confirmed -ne $row.itemCount -or $row.locked -ne 0)) {
                "Order $($row.orderId): paid but confirmation not settled."
            }
            # A definite Inventory rejection produces CANCELED + FAILED (4), not COMPENSATED (3).
            # Both are settled only without active/confirmed stock. RELEASED historical rows
            # are harmless here; scenario-specific tests may require zero historical rows too.
            if ($row.status -eq 3 -and ($row.locked -ne 0 -or $row.confirmed -ne 0 -or
                    $row.reservationStatus -notin @(3,4))) {
                "Order $($row.orderId): cancellation not settled."
            }
            if ($RequireSettled -and $row.status -eq 1) { "Order $($row.orderId): still pending payment." }
        }
        if ($RequireSettled -and $row.kind -eq 'outbox' -and $row.status -ne 2) {
            "Outbox for $($row.orderId): not delivered."
        }
    }
}
