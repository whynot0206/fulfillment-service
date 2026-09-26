#requires -Version 7.4
[CmdletBinding()]
param([string]$ProjectName = 'fulfillment-acceptance', [switch]$InitializeEmptyInstance)
. (Join-Path $PSScriptRoot 'common.ps1')
$context = Get-AcceptanceContext $ProjectName
[void](Get-AcceptanceSecret 'ACCEPTANCE_MYSQL_ROOT_PASSWORD')
$accounts = @(
    @{ User='fulfillment_order_app'; Database='fulfillment_order'; Password=(Get-AcceptanceSecret 'ACCEPTANCE_ORDER_DB_PASSWORD'); Permissions='SELECT, INSERT, UPDATE' },
    @{ User='fulfillment_inventory_app'; Database='fulfillment_inventory'; Password=(Get-AcceptanceSecret 'ACCEPTANCE_INVENTORY_DB_PASSWORD'); Permissions='SELECT, INSERT, UPDATE' },
    @{ User='fulfillment_commerce_app'; Database='fulfillment_commerce'; Password=(Get-AcceptanceSecret 'ACCEPTANCE_COMMERCE_DB_PASSWORD'); Permissions='SELECT, INSERT, UPDATE, DELETE' }
)
$files = @('sql/schema.sql', 'microservices/sql/migration-cycle7.sql',
    'microservices/sql/migration-cycle8.sql', 'microservices/sql/migration-cycle9.sql',
    'microservices/sql/migration-cycle10.sql', 'microservices/sql/migration-cycle11.sql',
    'microservices/sql/migration-cycle11-split-schema.sql', 'microservices/sql/migration-v2-commerce.sql',
    'microservices/sql/migration-v2-order-snapshot.sql', 'microservices/sql/seed-v2-commerce-demo.sql')
$hashes = foreach ($file in $files) {
    $path = Join-Path $context.Repository $file
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Migration is missing: $file" }
    (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
}
$fingerprint = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($hashes -join '|'))))
$mysql = Get-AcceptanceContainer $context 'mysql'
if (-not $mysql) { throw 'Start this project with start-infra.ps1 first.' }
Wait-AcceptanceContainer $mysql
$marker = Invoke-AcceptanceSql $mysql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='acceptance_control' AND table_name='bootstrap';"
if ($marker -eq '1') {
    $state = Invoke-AcceptanceSql $mysql "SELECT CONCAT(status,'|',fingerprint) FROM acceptance_control.bootstrap WHERE id=1;"
    if ($state -ne "READY|$fingerprint") {
        throw 'This instance has an incomplete or different bootstrap. Refusing destructive replay; inspect it or choose a new acceptance project.'
    }
    foreach ($account in $accounts) {
        Assert-AcceptanceAccount -Container $mysql -User $account.User -Database $account.Database -Password $account.Password
    }
    Save-AcceptanceJson -Path (Join-Path $context.Runtime 'database.json') -Value @{
        project = $ProjectName; mysqlContainer = $mysql; migrationFingerprint = $fingerprint
        completedAt = [DateTime]::UtcNow.ToString('o'); status = 'READY'
    }
    Write-Output 'Matching acceptance bootstrap is already complete; schema and seeds were not replayed.'
    & (Join-Path $PSScriptRoot 'migrate-reliability.ps1') -ProjectName $ProjectName
    exit 0
}
if (-not $InitializeEmptyInstance) { throw 'First initialization requires -InitializeEmptyInstance; existing data is never reset.' }
$tables = Invoke-AcceptanceSql $mysql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema NOT IN ('mysql','information_schema','performance_schema','sys');"
if ($tables -ne '0') { throw 'Refusing initialization: the acceptance container is not empty and has no completed ownership marker.' }
[void](Invoke-AcceptanceSql $mysql @"
CREATE DATABASE acceptance_control CHARACTER SET utf8mb4;
CREATE TABLE acceptance_control.bootstrap (id INT PRIMARY KEY, status VARCHAR(20) NOT NULL, fingerprint CHAR(64) NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP);
INSERT INTO acceptance_control.bootstrap VALUES (1, 'INITIALIZING', '$fingerprint', CURRENT_TIMESTAMP);
CREATE DATABASE fulfillment CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
"@ -Operation 'Create empty acceptance bootstrap marker')
foreach ($file in $files) {
    $sql = Get-Content -LiteralPath (Join-Path $context.Repository $file) -Raw -Encoding UTF8
    [void](Invoke-AcceptanceSql -Container $mysql -Sql ("USE fulfillment;`n" + $sql + "`n") -Operation "Apply $file")
    Write-Output "Applied $file"
}
foreach ($account in $accounts) {
    # '%' permits host-to-container traffic; port publishing is loopback-only and each
    # account is schema-scoped. No root access is granted to host application processes.
    $sql = "CREATE USER '$($account.User)'@'%' IDENTIFIED BY '$($account.Password)'; GRANT $($account.Permissions) ON $($account.Database).* TO '$($account.User)'@'%';"
    [void](Invoke-AcceptanceSql -Container $mysql -Sql $sql -Operation "Provision $($account.User)")
    Assert-AcceptanceAccount -Container $mysql -User $account.User -Database $account.Database -Password $account.Password
}
$shape = Invoke-AcceptanceSql $mysql "SELECT CONCAT((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='fulfillment_order' AND table_name='order_item' AND column_name IN ('name_snapshot','spec_snapshot')),'|',(SELECT COUNT(*) FROM fulfillment_commerce.product_sku),'|',(SELECT COUNT(*) FROM fulfillment_inventory.sku_stock));"
if ($shape -ne '2|5|5') { throw 'Unexpected bootstrap schema/seed shape; marker remains incomplete.' }
[void](Invoke-AcceptanceSql $mysql "UPDATE acceptance_control.bootstrap SET status='READY' WHERE id=1 AND status='INITIALIZING';")
Save-AcceptanceJson -Path (Join-Path $context.Runtime 'database.json') -Value @{
    project = $ProjectName; mysqlContainer = $mysql; migrationFingerprint = $fingerprint
    completedAt = [DateTime]::UtcNow.ToString('o'); status = 'READY'
}
Write-Output 'Isolated acceptance database initialized and account connections verified. No credentials were saved to files.'
& (Join-Path $PSScriptRoot 'migrate-reliability.ps1') -ProjectName $ProjectName
