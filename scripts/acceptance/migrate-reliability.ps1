#requires -Version 7.4
[CmdletBinding()]
param([string]$ProjectName = 'fulfillment-acceptance', [switch]$ResumeIncomplete)
. (Join-Path $PSScriptRoot 'common.ps1')
$context = Get-AcceptanceContext $ProjectName
[void](Get-AcceptanceSecret 'ACCEPTANCE_MYSQL_ROOT_PASSWORD')
$mysql = Get-AcceptanceContainer $context 'mysql'
if (-not $mysql) { throw 'Owned acceptance MySQL is missing.' }
$metadata = Get-Content (Join-Path $context.Runtime 'database.json') -Raw | ConvertFrom-Json
if ($metadata.project -ne $ProjectName -or $metadata.mysqlContainer -ne $mysql -or $metadata.status -ne 'READY') {
    throw 'Acceptance database ownership or bootstrap is unverified.'
}
if ((Invoke-AcceptanceSql $mysql "SELECT status FROM acceptance_control.bootstrap WHERE id=1;") -ne 'READY') {
    throw 'Completed acceptance bootstrap required; nothing migrated.'
}
$lock = [IO.File]::Open((Join-Path $context.Runtime 'migration.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$backendLock = $null
try {
    $backendLock = [IO.File]::Open((Join-Path $context.Runtime 'backend.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    [void](Invoke-AcceptanceSql $mysql @'
CREATE TABLE IF NOT EXISTS acceptance_control.incremental_migration (
 name VARCHAR(160) PRIMARY KEY, checksum CHAR(64) NOT NULL, status VARCHAR(20) NOT NULL,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
'@)
    foreach ($file in @('migration-v2-checkout-recovery.sql','migration-v2-outbox-lease-redrive.sql','migration-v2-cart-revision.sql')) {
        $path = Join-Path $context.Repository "microservices/sql/$file"
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        $record = Invoke-AcceptanceSql $mysql "SELECT CONCAT(status,'|',checksum) FROM acceptance_control.incremental_migration WHERE name='$file';"
        if ($record -eq "READY|$hash") { Write-Output "Already applied: $file"; continue }
        if ($record -and $record -notin @("APPLYING|$hash")) { throw "Migration checksum/state mismatch: $file" }
        if ($record -and -not $ResumeIncomplete) { throw "Incomplete migration: $file. Review first; -ResumeIncomplete reruns only the same idempotent SQL." }
        $processRegistry = Join-Path $context.Runtime 'backend-processes.json'
        if (Test-Path -LiteralPath $processRegistry) {
            $registry = Get-Content -LiteralPath $processRegistry -Raw | ConvertFrom-Json
            if ($registry.project -ne $ProjectName) { throw 'Backend ownership mismatch.' }
            foreach ($processRecord in @($registry.processes | Where-Object service -in @('commerce-service','order-service'))) {
                if (Get-AcceptanceOwnedProcess $processRecord) {
                    throw 'Stop owned Commerce and Order before pending reliability migrations; old and new writers must not overlap.'
                }
            }
        }
        [void](Invoke-AcceptanceSql $mysql "INSERT IGNORE INTO acceptance_control.incremental_migration(name,checksum,status) VALUES ('$file','$hash','APPLYING');")
        [void](Invoke-AcceptanceSql $mysql (Get-Content -LiteralPath $path -Raw -Encoding UTF8) -Operation "Incremental migration $file")
        [void](Invoke-AcceptanceSql $mysql "UPDATE acceptance_control.incremental_migration SET status='READY' WHERE name='$file' AND checksum='$hash' AND status='APPLYING';")
        Write-Output "Applied incremental migration: $file"
    }
} finally { if ($backendLock) { $backendLock.Dispose() }; $lock.Dispose() }
Write-Output 'Incremental schema changes complete. Historical bootstrap and demo records were not replayed.'
