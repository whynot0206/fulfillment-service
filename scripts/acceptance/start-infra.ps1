#requires -Version 7.4
[CmdletBinding()]
param(
    [string]$ProjectName = 'fulfillment-acceptance',
    [ValidateRange(1024,65535)][int]$MySqlPort = 13306,
    [ValidateRange(1024,65535)][int]$RedisPort = 16379
)
. (Join-Path $PSScriptRoot 'common.ps1')
$context = Get-AcceptanceContext $ProjectName
[void](Get-AcceptanceSecret 'ACCEPTANCE_MYSQL_ROOT_PASSWORD')
if ($MySqlPort -in @(3306,6379) -or $RedisPort -in @(3306,6379) -or $MySqlPort -eq $RedisPort) {
    throw 'Use distinct acceptance ports, never the existing 3306/6379.'
}
[void](Invoke-AcceptanceDocker -Arguments @('info', '--format', '{{.ServerVersion}}') -Operation 'Verify Docker daemon')
$mapping = @{ mysql = @{ Host = $MySqlPort; Container = '3306/tcp' }; redis = @{ Host = $RedisPort; Container = '6379/tcp' } }
foreach ($service in @('mysql','redis')) {
    $existing = Get-AcceptanceContainer $context $service
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $mapping[$service].Host -ErrorAction SilentlyContinue)
    if ($existing) {
        $published = Invoke-AcceptanceDocker -Arguments @('inspect', '--format', '{{json .HostConfig.PortBindings}}', $existing) | ConvertFrom-Json -AsHashtable
        $binding = @($published[$mapping[$service].Container])
        if ($binding.Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1' -or [int]$binding[0].HostPort -ne $mapping[$service].Host) {
            throw "Existing $service container has different port bindings. Do not silently recreate it."
        }
    } elseif ($listeners.Count -gt 0) {
        throw "Acceptance port $($mapping[$service].Host) is already in use; no process will be stopped."
    }
}
$environment = @{ ACCEPTANCE_MYSQL_PORT = "$MySqlPort"; ACCEPTANCE_REDIS_PORT = "$RedisPort" }
$arguments = @(Get-AcceptanceComposeArguments $context) + @('up', '-d', '--no-recreate', 'mysql', 'redis')
[void](Invoke-AcceptanceDocker -Arguments $arguments -Environment $environment -TimeoutSeconds 600 -Operation 'Start isolated acceptance dependencies')
$mysql = Get-AcceptanceContainer $context 'mysql'
$redis = Get-AcceptanceContainer $context 'redis'
Wait-AcceptanceContainer $mysql
Wait-AcceptanceContainer $redis
Save-AcceptanceJson -Path (Join-Path $context.Runtime 'infrastructure.json') -Value @{
    project = $ProjectName; mysqlContainer = $mysql; redisContainer = $redis
    mysqlPort = $MySqlPort; redisPort = $RedisPort; recordedAt = [DateTime]::UtcNow.ToString('o')
}
Write-Output "Acceptance dependencies healthy: MySQL 127.0.0.1:$MySqlPort; Redis 127.0.0.1:$RedisPort. Existing data/services were not reset."
