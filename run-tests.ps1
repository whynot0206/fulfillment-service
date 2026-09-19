param(
    [string[]]$MavenArgs = @('test')
)

$workspaceRoot = Split-Path $PSScriptRoot -Parent
$toolRoot = Join-Path $workspaceRoot '.toolchains'
$jdkHome = Get-ChildItem (Join-Path $toolRoot 'jdk-17') -Directory |
    Select-Object -First 1 -ExpandProperty FullName
$maven = Join-Path $toolRoot 'maven\apache-maven-3.9.16\bin\mvn.cmd'
$repository = Join-Path $workspaceRoot '.m2\repository'

if (-not $jdkHome -or -not (Test-Path $maven)) {
    throw 'Project JDK or Maven was not found under D:\vibecoding\.toolchains'
}

$env:JAVA_HOME = $jdkHome
& $maven "-Dmaven.repo.local=$repository" @MavenArgs
exit $LASTEXITCODE
