#requires -Version 7.4
[CmdletBinding()]
param([string]$ProjectName = 'fulfillment-acceptance', [switch]$CreateIfMissing)
. (Join-Path $PSScriptRoot 'common.ps1')
if (-not $IsWindows) { throw 'This helper uses Windows current-user DPAPI and must run on Windows.' }
$context = Get-AcceptanceContext $ProjectName
$secretPath = Join-Path $context.Runtime 'secrets.dpapi.json'
$names = @('ACCEPTANCE_MYSQL_ROOT_PASSWORD','ACCEPTANCE_ORDER_DB_PASSWORD','ACCEPTANCE_INVENTORY_DB_PASSWORD',
    'ACCEPTANCE_COMMERCE_DB_PASSWORD','ACCEPTANCE_INTERNAL_SERVICE_TOKEN','ACCEPTANCE_AUTH_JWT_SECRET','ACCEPTANCE_PAYMENT_CALLBACK_SECRET')
if (-not (Test-Path -LiteralPath $secretPath)) {
    if (-not $CreateIfMissing) { throw 'Acceptance secrets do not exist. First creation requires -CreateIfMissing.' }
    [void](New-Item -ItemType Directory -Path $context.Runtime -Force)
    $encrypted = @{}
    foreach ($name in $names) {
        $bytes = [byte[]]::new(36)
        [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
        $plain = [Convert]::ToBase64String($bytes)
        $secure = ConvertTo-SecureString -String $plain -AsPlainText -Force
        try { $encrypted[$name] = ConvertFrom-SecureString -SecureString $secure } finally { $secure.Dispose() }
        $plain = $null
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
    $document = @{ version=1; project=$ProjectName; protection='Windows-DPAPI-CurrentUser'; values=$encrypted }
    # No plaintext credential file is created. CreateNew also prevents concurrent overwrite.
    $stream = [IO.File]::Open($secretPath, 'CreateNew', 'Write', 'None')
    try {
        $data = [Text.UTF8Encoding]::new($false).GetBytes(($document | ConvertTo-Json -Depth 4))
        $stream.Write($data, 0, $data.Length)
    } finally { $stream.Dispose() }
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = [Security.AccessControl.FileSecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetOwner($identity)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'Allow'))
    Set-Acl -LiteralPath $secretPath -AclObject $acl
    Write-Output 'Created encrypted, current-user-only acceptance credentials; existing credentials were not rotated.'
}
$stored = Get-Content -LiteralPath $secretPath -Raw | ConvertFrom-Json -AsHashtable
if ($stored.version -ne 1 -or $stored.project -ne $ProjectName -or $stored.protection -ne 'Windows-DPAPI-CurrentUser') {
    throw 'Encrypted credential metadata does not match this acceptance project.'
}
# Validate the complete set before modifying the calling process environment.
$decoded = @{}
try {
    foreach ($name in $names) {
        if (-not $stored['values'].ContainsKey($name)) { throw 'Missing credential.' }
        $secure = ConvertTo-SecureString -String $stored['values'][$name]
        try { $decoded[$name] = [Net.NetworkCredential]::new('', $secure).Password } finally { $secure.Dispose() }
        if ($decoded[$name] -notmatch '^[A-Za-z0-9_+=./-]{32,128}$') { throw 'Invalid credential.' }
    }
} catch { throw 'Cannot load acceptance credentials. Use the same Windows account/machine; do not overwrite the file to bypass this error.' }
foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $decoded[$name], 'Process') }
$decoded.Clear()
Write-Output 'Acceptance credentials loaded into this process only. No secret values were printed.'
