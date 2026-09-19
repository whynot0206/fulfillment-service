param(
    [ValidateSet('db', 'redis')]
    [string]$Mode,
    [long]$BaseOrderId,
    [int]$Threads = 100,
    [int]$RampSeconds = 10,
    [int]$DurationSeconds = 30,
    [int]$Port = 18080,
    [string]$RunName = ''
)

$workspaceRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$toolRoot = Join-Path $workspaceRoot '.toolchains'
$jdkHome = Get-ChildItem (Join-Path $toolRoot 'jdk-17') -Directory |
    Select-Object -First 1 -ExpandProperty FullName
$jmeter = Join-Path $toolRoot 'apache-jmeter-5.6.3\bin\jmeter.bat'
$plan = Join-Path $PSScriptRoot 'cycle5-order-comparison.jmx'

if (-not $jdkHome -or -not (Test-Path -LiteralPath $jmeter)) {
    throw 'JDK 17 or Apache JMeter 5.6.3 is missing under D:\vibecoding\.toolchains'
}

if ([string]::IsNullOrWhiteSpace($RunName)) {
    $RunName = '{0}-{1}' -f $Mode, (Get-Date -Format 'yyyyMMdd-HHmmss')
}

$resultRoot = Join-Path $PSScriptRoot 'results'
$jtl = Join-Path $resultRoot ($RunName + '.jtl')
$report = Join-Path $resultRoot ($RunName + '-report')
if ((Test-Path -LiteralPath $jtl) -or (Test-Path -LiteralPath $report)) {
    throw "Result already exists: $RunName"
}
New-Item -ItemType Directory -Path $resultRoot -Force | Out-Null

$path = if ($Mode -eq 'db') { '/api/orders' } else { '/api/orders/redis' }
$expectedCode = if ($Mode -eq 'db') { 201 } else { 202 }

$env:JAVA_HOME = $jdkHome
$env:PATH = "$jdkHome\bin;$env:PATH"
& $jmeter -n -t $plan -l $jtl -e -o $report `
    "-Jport=$Port" "-Jpath=$path" "-JexpectedCode=$expectedCode" `
    "-JbaseOrderId=$BaseOrderId" "-Jthreads=$Threads" `
    "-JrampSeconds=$RampSeconds" "-JdurationSeconds=$DurationSeconds"
$jmeterExitCode = $LASTEXITCODE
if ($jmeterExitCode -ne 0 -or -not (Test-Path -LiteralPath $jtl)) {
    throw "JMeter run failed with exit code $jmeterExitCode"
}
exit 0
