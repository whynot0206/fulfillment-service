#requires -Version 7.4
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-AcceptanceContext {
    param([string]$ProjectName = 'fulfillment-acceptance')
    if ($ProjectName -notmatch '^fulfillment-acceptance(?:-[a-z0-9][a-z0-9-]*)?$') {
        throw 'ProjectName must be fulfillment-acceptance or a fulfillment-acceptance-* name.'
    }
    $repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    [pscustomobject]@{
        ProjectName = $ProjectName
        Repository = $repository
        ComposeFile = Join-Path $PSScriptRoot 'compose.acceptance.yml'
        Runtime = Join-Path $repository ".runtime/acceptance/$ProjectName"
    }
}

function Get-AcceptanceSecret {
    param([Parameter(Mandatory)][string]$Name, [int]$MinimumLength = 24)
    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    # A restricted generated-password alphabet prevents SQL and shell interpolation.
    # Base64/hex generated secrets work; errors never include the supplied value.
    if ([string]::IsNullOrWhiteSpace($value) -or $value.Length -lt $MinimumLength -or
        $value.Length -gt 128 -or $value -notmatch '^[A-Za-z0-9_+=./-]+$') {
        throw "$Name must be a generated secret of $MinimumLength..128 characters (base64/hex alphabet)."
    }
    return $value
}

function Invoke-AcceptanceDocker {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [string]$InputText,
        [hashtable]$Environment = @{},
        [int]$TimeoutSeconds = 60,
        [string]$Operation = 'Docker operation'
    )
    $docker = (Get-Command docker -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $docker
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    $info.Environment['COMPOSE_DISABLE_ENV_FILE'] = '1'
    foreach ($key in $Environment.Keys) { $info.Environment[$key] = [string]$Environment[$key] }
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    try {
        [void]$process.Start()
        $outTask = $process.StandardOutput.ReadToEndAsync()
        $errTask = $process.StandardError.ReadToEndAsync()
        if ($null -ne $InputText) { $process.StandardInput.Write($InputText) }
        $process.StandardInput.Close()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            # This is only the docker CLI process created above, never a service/container.
            $process.Kill()
            throw "$Operation timed out; dependency state is unknown, not automatically rolled back."
        }
        $output = $outTask.GetAwaiter().GetResult()
        [void]$errTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            # SQL/client errors can contain submitted text: never echo raw stderr or secrets.
            throw "$Operation failed (exit $($process.ExitCode)); no secret-bearing output was printed."
        }
        return $output.Trim()
    } finally { $process.Dispose() }
}

function Get-AcceptanceComposeArguments {
    param([Parameter(Mandatory)]$Context)
    @('compose', '--project-name', $Context.ProjectName, '--project-directory', $PSScriptRoot,
      '-f', $Context.ComposeFile)
}

function Get-AcceptanceContainer {
    param([Parameter(Mandatory)]$Context, [ValidateSet('mysql','redis')][string]$Service)
    $arguments = @(Get-AcceptanceComposeArguments $Context) + @('ps', '-a', '-q', $Service)
    $container = Invoke-AcceptanceDocker -Arguments $arguments -Operation "Locate $Service acceptance container"
    if ([string]::IsNullOrWhiteSpace($container)) { return $null }
    if ($container -notmatch '^[a-f0-9]{12,64}$') { throw 'Expected exactly one acceptance container.' }
    $template = '{{ index .Config.Labels "com.docker.compose.project" }}|{{ index .Config.Labels "com.docker.compose.service" }}'
    $labels = Invoke-AcceptanceDocker -Arguments @('inspect', '--format', $template, $container)
    if ($labels -ne "$($Context.ProjectName)|$Service") { throw 'Container ownership validation failed.' }
    return $container
}

function Wait-AcceptanceContainer {
    param([Parameter(Mandatory)][string]$Container, [int]$TimeoutSeconds = 180)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $health = Invoke-AcceptanceDocker -Arguments @('inspect', '--format', '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}', $Container)
        if ($health -eq 'healthy') { return }
        if ($health -eq 'exited' -or $health -eq 'dead') { throw 'Acceptance dependency exited before becoming healthy.' }
        Start-Sleep -Seconds 2
    }
    throw 'Acceptance dependency did not become healthy before the deadline.'
}

function Invoke-AcceptanceSql {
    param([Parameter(Mandatory)][string]$Container, [Parameter(Mandatory)][string]$Sql,
          [string]$Operation = 'Acceptance SQL')
    # Root credentials stay inside the container environment, not in command arguments/files.
    $shell = 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --protocol=socket --user=root --batch --skip-column-names --default-character-set=utf8mb4'
    Invoke-AcceptanceDocker -Arguments @('exec', '-i', $Container, 'sh', '-c', $shell) -InputText $Sql -Operation $Operation
}

function Assert-AcceptanceAccount {
    param([Parameter(Mandatory)][string]$Container, [Parameter(Mandatory)][string]$User,
          [Parameter(Mandatory)][string]$Database, [Parameter(Mandatory)][string]$Password)
    $result = Invoke-AcceptanceDocker -Arguments @('exec', '-i', '--env', 'MYSQL_PWD', $Container,
        'mysql', '--protocol=TCP', '--host=127.0.0.1', "--user=$User", "--database=$Database",
        '--batch', '--skip-column-names') -Environment @{ MYSQL_PWD = $Password } -InputText "SELECT 1;`n" -Operation "Verify $User connection"
    if ($result -ne '1') { throw "Unexpected connection result for $User." }
}

function Save-AcceptanceJson {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)]$Value)
    # Only non-secret process/port/container metadata may be passed here.
    [void](New-Item -ItemType Directory -Path (Split-Path $Path -Parent) -Force)
    $json = ConvertTo-Json -InputObject $Value -Depth 8
    [IO.File]::WriteAllText($Path, $json, [Text.UTF8Encoding]::new($false))
}

function Get-AcceptanceOwnedProcess {
    param([Parameter(Mandatory)]$Record)
    $process = Get-Process -Id ([int]$Record.processId) -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    # PowerShell 7.5+ can deserialize ISO JSON dates as DateTime, while 7.4 keeps
    # strings. Compare UTC ticks, never a formatted string against a DateTime.
    $started = $process.StartTime.ToUniversalTime().Ticks
    $recordedStart = ([datetime]$Record.startedAtUtc).ToUniversalTime().Ticks
    if ($started -ne $recordedStart -or $process.Path -ne $Record.javaPath) {
        throw 'Recorded PID was reused or executable identity changed; refusing to manage that process.'
    }
    return $process
}

function Wait-AcceptanceHttpHealth {
    param([Parameter(Mandatory)][Diagnostics.Process]$Process, [Parameter(Mandatory)][int]$Port,
          [int]$TimeoutSeconds = 120)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $Process.Refresh()
        if ($Process.HasExited) { throw "Owned Java process $($Process.Id) exited; inspect its recorded local logs without publishing credentials." }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 3 -NoProxy
            if ($health.status -eq 'UP') { return }
        } catch {
            # Starting apps may refuse connections or report DOWN; retry until bounded deadline.
        }
        Start-Sleep -Seconds 2
    }
    throw "Acceptance service on port $Port did not become healthy. Owned processes remain recorded for explicit cleanup."
}
