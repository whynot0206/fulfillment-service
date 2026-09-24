param(
    [string[]]$MavenArgs = @('test')
)

$workspaceRoot = Split-Path $PSScriptRoot -Parent
$toolRoot = Join-Path $workspaceRoot '.toolchains'
$jdkHome = Get-ChildItem (Join-Path $toolRoot 'jdk-17') -Directory |
    Select-Object -First 1 -ExpandProperty FullName
$maven = Join-Path $toolRoot 'maven\apache-maven-3.9.16\bin\mvn.cmd'
$repository = Join-Path $workspaceRoot '.m2\repository'
$localEnv = Join-Path $PSScriptRoot '.env'

if (-not $env:MYSQL_PASSWORD -and (Test-Path -LiteralPath $localEnv)) {
    $rootPasswordLine = Get-Content -LiteralPath $localEnv |
        Where-Object { $_ -match '^MYSQL_ROOT_PASSWORD=' } |
        Select-Object -First 1
    if ($rootPasswordLine) {
        $env:MYSQL_PASSWORD = $rootPasswordLine.Substring('MYSQL_ROOT_PASSWORD='.Length)
    }
}

if (-not $jdkHome -or -not (Test-Path $maven)) {
    throw 'Project JDK or Maven was not found under D:\vibecoding\.toolchains'
}

$env:JAVA_HOME = $jdkHome
& $maven "-Dmaven.repo.local=$repository" @MavenArgs
exit $LASTEXITCODE
