#requires -Version 7.4
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$JavaPath,
    [string]$ProjectName = 'fulfillment-acceptance',
    [hashtable]$JarPaths = @{},
    [ValidateRange(1024,65535)][int]$GatewayPort = 18080,
    [ValidateRange(1024,65535)][int]$OrderPort = 18081,
    [ValidateRange(1024,65535)][int]$InventoryPort = 18082,
    [ValidateRange(1024,65535)][int]$PaymentPort = 18083,
    [ValidateRange(1024,65535)][int]$CommercePort = 18084,
    [ValidateRange(128,4096)][int]$HeapMb = 512,
    [ValidateRange(1,604800)][int]$OrderTimeoutSeconds = 1800,
    [switch]$UseFaultProxies,
    [ValidateRange(20,600)][int]$HealthTimeoutSeconds = 120
)
. (Join-Path $PSScriptRoot 'common.ps1')
$context = Get-AcceptanceContext $ProjectName
if ($UseFaultProxies -and ($OrderPort -ne 18081 -or $InventoryPort -ne 18082)) {
    throw 'Fault proxies use the documented fixed upstream ports 18081/18082.'
}
$java = (Resolve-Path -LiteralPath $JavaPath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or [IO.Path]::GetFileName($java) -notin @('java.exe','java')) {
    throw 'JavaPath must name the Java executable inside the selected JDK 17.'
}
$versionText = (& $java '-version' 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $versionText -notmatch 'version "17[.]') {
    throw 'Acceptance requires an explicitly selected JDK 17; no system Java is silently substituted.'
}
$infraPath = Join-Path $context.Runtime 'infrastructure.json'
$databasePath = Join-Path $context.Runtime 'database.json'
if (-not (Test-Path -LiteralPath $infraPath) -or -not (Test-Path -LiteralPath $databasePath)) {
    throw 'Run start-infra.ps1 and initialize-db.ps1 successfully for this project first.'
}
$infra = Get-Content -LiteralPath $infraPath -Raw | ConvertFrom-Json
$database = Get-Content -LiteralPath $databasePath -Raw | ConvertFrom-Json
if ($infra.project -ne $ProjectName -or $database.project -ne $ProjectName -or $database.status -ne 'READY') {
    throw 'Acceptance runtime metadata belongs to a different or incomplete deployment.'
}
$mysql = Get-AcceptanceContainer $context 'mysql'
$redis = Get-AcceptanceContainer $context 'redis'
if ($mysql -ne $infra.mysqlContainer -or $redis -ne $infra.redisContainer -or $mysql -ne $database.mysqlContainer) {
    throw 'Dependency container identity changed; revalidate initialization before starting applications.'
}
Wait-AcceptanceContainer $mysql
Wait-AcceptanceContainer $redis
$orderPassword = Get-AcceptanceSecret 'ACCEPTANCE_ORDER_DB_PASSWORD'
$inventoryPassword = Get-AcceptanceSecret 'ACCEPTANCE_INVENTORY_DB_PASSWORD'
$commercePassword = Get-AcceptanceSecret 'ACCEPTANCE_COMMERCE_DB_PASSWORD'
$internalToken = Get-AcceptanceSecret 'ACCEPTANCE_INTERNAL_SERVICE_TOKEN'
$jwtSecret = Get-AcceptanceSecret 'ACCEPTANCE_AUTH_JWT_SECRET' -MinimumLength 32
$paymentSecret = Get-AcceptanceSecret 'ACCEPTANCE_PAYMENT_CALLBACK_SECRET'
Assert-AcceptanceAccount $mysql 'fulfillment_order_app' 'fulfillment_order' $orderPassword
Assert-AcceptanceAccount $mysql 'fulfillment_inventory_app' 'fulfillment_inventory' $inventoryPassword
Assert-AcceptanceAccount $mysql 'fulfillment_commerce_app' 'fulfillment_commerce' $commercePassword
$migrationsReady = Invoke-AcceptanceSql $mysql "SELECT COUNT(*) FROM acceptance_control.incremental_migration WHERE status='READY' AND name IN ('migration-v2-checkout-recovery.sql','migration-v2-outbox-lease-redrive.sql','migration-v2-cart-revision.sql');" -Operation 'Check reliability migrations'
if ($migrationsReady -ne '3') { throw 'Apply reliability migrations with old Commerce/Order stopped before starting this version.' }
$specifications = @(
    @{ Name='inventory-service'; Port=$InventoryPort; Schema='fulfillment_inventory'; User='fulfillment_inventory_app'; Password=$inventoryPassword; PasswordVariable='INVENTORY_DB_PASSWORD'; UrlVariable='INVENTORY_DATASOURCE_URL'; UserVariable='INVENTORY_DB_USERNAME' },
    @{ Name='order-service'; Port=$OrderPort; Schema='fulfillment_order'; User='fulfillment_order_app'; Password=$orderPassword; PasswordVariable='ORDER_DB_PASSWORD'; UrlVariable='ORDER_DATASOURCE_URL'; UserVariable='ORDER_DB_USERNAME' },
    @{ Name='payment-service'; Port=$PaymentPort; Schema=''; User=''; Password=''; PasswordVariable=''; UrlVariable=''; UserVariable='' },
    @{ Name='commerce-service'; Port=$CommercePort; Schema='fulfillment_commerce'; User='fulfillment_commerce_app'; Password=$commercePassword; PasswordVariable='COMMERCE_DB_PASSWORD'; UrlVariable='COMMERCE_DATASOURCE_URL'; UserVariable='COMMERCE_DB_USERNAME' },
    @{ Name='gateway'; Port=$GatewayPort; Schema=''; User=''; Password=''; PasswordVariable=''; UrlVariable=''; UserVariable='' }
)
if (@($specifications.Port | Sort-Object -Unique).Count -ne 5 -or
    @($specifications.Port | Where-Object { $_ -in @(3306,6379,[int]$infra.mysqlPort,[int]$infra.redisPort) }).Count -gt 0) {
    throw 'All backend ports must be distinct from each other and from database/Redis ports.'
}
foreach ($specification in $specifications) {
    $name = $specification.Name
    if ($JarPaths.ContainsKey($name)) {
        $jar = (Resolve-Path -LiteralPath $JarPaths[$name] -ErrorAction Stop).Path
    } else {
        $target = Join-Path $context.Repository "microservices/$name/target"
        $candidates = @(Get-ChildItem -LiteralPath $target -File -Filter "$name-*.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch '-(sources|javadoc|tests)[.]jar$' })
        if ($candidates.Count -ne 1) { throw "Provide exactly one executable jar for $name via -JarPaths, or package its target directory first." }
        $jar = $candidates[0].FullName
    }
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Jar is missing for $name." }
    $specification['Jar'] = $jar
}
[void](New-Item -ItemType Directory -Path $context.Runtime -Force)
$lock = [IO.File]::Open((Join-Path $context.Runtime 'backend.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
try {
    $registryPath = Join-Path $context.Runtime 'backend-processes.json'
    $records = @()
    if (Test-Path -LiteralPath $registryPath) {
        $saved = Get-Content -LiteralPath $registryPath -Raw | ConvertFrom-Json
        if ($saved.project -ne $ProjectName) { throw 'Backend registry project does not match.' }
        $records = @($saved.processes)
    }
    # Validate every port before starting any new process. Never kill a listener.
    foreach ($specification in $specifications) {
        $record = @($records | Where-Object { $_.service -eq $specification.Name }) | Select-Object -First 1
        $owned = if ($record) { Get-AcceptanceOwnedProcess $record } else { $null }
        if ($owned -and ($record.javaPath -ne $java -or $record.jarPath -ne $specification.Jar -or [int]$record.port -ne $specification.Port)) {
            throw "Existing owned process for $($specification.Name) uses different paths/ports; stop it explicitly before changing them."
        }
        if ($owned -and $specification.Name -in @('order-service','commerce-service')) {
            $existingFaultMode = $record.PSObject.Properties['usesFaultProxy'] -and [bool]$record.usesFaultProxy
            if ([bool]$existingFaultMode -ne [bool]$UseFaultProxies) {
                throw "Stop owned $($specification.Name) before switching fault-proxy mode."
            }
        }
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $specification.Port -ErrorAction SilentlyContinue)
        foreach ($listener in $listeners) {
            if (-not $owned -or $listener.OwningProcess -ne $owned.Id) {
                throw "Port $($specification.Port) is occupied by an unowned process; nothing will be stopped."
            }
        }
    }
    foreach ($specification in $specifications) {
        $name = $specification.Name
        $record = @($records | Where-Object { $_.service -eq $name }) | Select-Object -First 1
        $process = if ($record) { Get-AcceptanceOwnedProcess $record } else { $null }
        if (-not $process) {
            $environment = @{
                SERVER_ADDRESS='127.0.0.1'; SERVER_PORT="$($specification.Port)"
                GATEWAY_PORT="$GatewayPort"; ORDER_SERVICE_PORT="$OrderPort"; PAYMENT_SERVICE_PORT="$PaymentPort"
                REDIS_HOST='127.0.0.1'; REDIS_PORT="$($infra.redisPort)"
                SPRING_DATA_REDIS_HOST='127.0.0.1'; SPRING_DATA_REDIS_PORT="$($infra.redisPort)"; SPRING_DATA_REDIS_DATABASE='0'
                ORDER_SERVICE_URL="http://127.0.0.1:$OrderPort"; INVENTORY_SERVICE_URL="http://127.0.0.1:$InventoryPort"
                COMMERCE_SERVICE_URL="http://127.0.0.1:$CommercePort"; PAYMENT_SERVICE_URL="http://127.0.0.1:$PaymentPort"
                INTERNAL_SERVICE_TOKEN=$internalToken; AUTH_JWT_SECRET=$null; AUTH_JWT_ISSUER='fulfillment-acceptance'
                PAYMENT_CALLBACK_SECRET=$null; COMMERCE_NODE_ID='0'; COMMERCE_ORDER_TIMEOUT_SECONDS="$OrderTimeoutSeconds"
                GATEWAY_LEGACY_ORDER_CREATE_ENABLED='false'
                # The Vite proxy forwards Origin. Both documented loopback spellings
                # must be accepted by Commerce's existing explicit CORS whitelist.
                COMMERCE_CORS_ALLOWED_ORIGINS='http://127.0.0.1:5173,http://localhost:5173'
                SPRING_DATA_REDIS_PASSWORD=$null; SPRING_DATA_REDIS_USERNAME=$null; SPRING_DATA_REDIS_URL=$null
                SPRING_CONFIG_LOCATION='classpath:/application.yml'; SPRING_PROFILES_ACTIVE='acceptance'
                SPRING_CONFIG_ADDITIONAL_LOCATION=$null; SPRING_CONFIG_IMPORT=$null; SPRING_CONFIG_NAME=$null
                SPRING_PROFILES_INCLUDE=$null; SPRING_PROFILES_DEFAULT=$null
                SPRING_DATA_REDIS_CLUSTER_NODES=$null; SPRING_DATA_REDIS_SENTINEL_NODES=$null
                SPRING_DATA_REDIS_SENTINEL_MASTER=$null; SPRING_DATA_REDIS_SENTINEL_USERNAME=$null; SPRING_DATA_REDIS_SENTINEL_PASSWORD=$null
                SPRING_APPLICATION_JSON=$null; JAVA_TOOL_OPTIONS=$null; JDK_JAVA_OPTIONS=$null; _JAVA_OPTIONS=$null
                SPRING_DATASOURCE_URL=$null; SPRING_DATASOURCE_USERNAME=$null; SPRING_DATASOURCE_PASSWORD=$null
                ORDER_DB_PASSWORD=$null; INVENTORY_DB_PASSWORD=$null; COMMERCE_DB_PASSWORD=$null; MYSQL_PASSWORD=$null; MYSQL_ROOT_PASSWORD=$null
            }
            # Remove every inherited member, including indexed imports/nodes and
            # profile-group names. Explicit acceptance values above always win.
            # Normalize dotted/dashed aliases for matching, without reading or logging values.
            foreach ($inheritedName in [Environment]::GetEnvironmentVariables('Process').Keys) {
                $normalizedName = ([string]$inheritedName).ToUpperInvariant().Replace('.', '_').Replace('-', '_')
                if ($normalizedName -match '^SPRING_(?:(?:CONFIG|PROFILES)_|(?:DATA_)?REDIS_(?:CLUSTER|SENTINEL)(?:_|$))' -and
                    -not $environment.ContainsKey([string]$inheritedName)) {
                    $environment[[string]$inheritedName] = $null
                }
            }
            # Acceptance secrets are not inherited under their bootstrap names by applications.
            foreach ($secretName in @('ACCEPTANCE_MYSQL_ROOT_PASSWORD','ACCEPTANCE_ORDER_DB_PASSWORD','ACCEPTANCE_INVENTORY_DB_PASSWORD',
                    'ACCEPTANCE_COMMERCE_DB_PASSWORD','ACCEPTANCE_INTERNAL_SERVICE_TOKEN','ACCEPTANCE_AUTH_JWT_SECRET','ACCEPTANCE_PAYMENT_CALLBACK_SECRET')) {
                $environment[$secretName] = $null
            }
            if ($name -in @('commerce-service','gateway')) { $environment['AUTH_JWT_SECRET'] = $jwtSecret }
            if ($UseFaultProxies) {
                # Test-only loopback hops. Gateway stays direct; only service-to-service calls cross faults.
                if ($name -eq 'commerce-service') { $environment['ORDER_SERVICE_URL'] = 'http://127.0.0.1:18881' }
                if ($name -eq 'order-service') { $environment['INVENTORY_SERVICE_URL'] = 'http://127.0.0.1:18882' }
            }
            if ($name -eq 'payment-service') { $environment['PAYMENT_CALLBACK_SECRET'] = $paymentSecret }
            if ($specification.Schema) {
                $url = "jdbc:mysql://127.0.0.1:$($infra.mysqlPort)/$($specification.Schema)?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
                $environment[$specification.UrlVariable] = $url
                $environment[$specification.UserVariable] = $specification.User
                $environment[$specification.PasswordVariable] = $specification.Password
                $environment['SPRING_DATASOURCE_URL'] = $url
                $environment['SPRING_DATASOURCE_USERNAME'] = $specification.User
                $environment['SPRING_DATASOURCE_PASSWORD'] = $specification.Password
            }
            $logDirectory = Join-Path $context.Runtime 'logs'
            [void](New-Item -ItemType Directory -Path $logDirectory -Force)
            $runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfff') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
            $stdout = Join-Path $logDirectory "$name-$runId.stdout.log"
            $stderr = Join-Path $logDirectory "$name-$runId.stderr.log"
            # Only jar path and heap flags are command-line arguments, never credentials.
            $arguments = "-Xms128m -Xmx${HeapMb}m -jar `"$($specification.Jar)`""
            $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $context.Repository `
                -Environment $environment -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
            $record = [pscustomobject]@{
                service=$name; processId=$process.Id; startedAtUtc=$process.StartTime.ToUniversalTime().ToString('o')
                javaPath=$java; jarPath=$specification.Jar; port=$specification.Port; stdout=$stdout; stderr=$stderr
                usesFaultProxy=([bool]$UseFaultProxies -and $name -in @('order-service','commerce-service'))
            }
            $records = @($records | Where-Object { $_.service -ne $name }) + @($record)
            Save-AcceptanceJson -Path $registryPath -Value @{ project=$ProjectName; processes=@($records) }
        } else {
            Write-Warning "Existing $name process is reused. Config changes or a rebuilt jar at the same path are not applied; stop selected owned services before changing config/jar, then start them again."
        }
        Wait-AcceptanceHttpHealth -Process $process -Port $specification.Port -TimeoutSeconds $HealthTimeoutSeconds
        Write-Output "$name healthy at 127.0.0.1:$($specification.Port), owned PID $($process.Id)."
    }
    Write-Output 'All five acceptance services are healthy. This does not replace business/browser acceptance.'
} finally { $lock.Dispose() }
