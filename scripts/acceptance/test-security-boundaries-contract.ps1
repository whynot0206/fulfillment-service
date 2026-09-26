#requires -Version 7.4
# Offline only: load selected AST function definitions, never the operational script body.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$path=Join-Path $PSScriptRoot 'test-security-boundaries.ps1'
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Security script has syntax errors.'}
$source=Get-Content -LiteralPath $path -Raw
$count=0
function Assert-Offline([string]$Name,[bool]$Ok){
    if(-not $Ok){throw "Offline security contract failed: $Name"}
    $script:count++
}
function Load-Function([string]$Name){
    $definition=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $Name},$true))
    if($definition.Count -ne 1){throw "Expected one definition for $Name"}
    return [scriptblock]::Create($definition[0].Extent.Text)
}
foreach($name in @('Encode-Base64Url','Decode-Base64Url','Hmac-Bytes','New-ExpiredJwt','New-CallbackHeaders','Assert-Id','Order-Fact','Wait-Settled','Check','Http')){
    . (Load-Function $name)
}
$checks=[Collections.Generic.List[object]]::new()
$bytes=[byte[]](0..255)
$encoded=Encode-Base64Url $bytes
Assert-Offline 'URL encoding excludes padding and unsafe alphabet' ($encoded -notmatch '[=+/]')
Assert-Offline 'URL encoding round-trips full byte range' ([Convert]::ToHexString((Decode-Base64Url $encoded)) -ceq [Convert]::ToHexString($bytes))
$known=[Convert]::ToHexString((Hmac-Bytes 'key' 'The quick brown fox jumps over the lazy dog')).ToLowerInvariant()
Assert-Offline 'HMAC matches independent standard SHA256 vector' ($known -ceq 'f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8')
$fixtureSecret='unit-only-non-production-test-secret'
$header=Encode-Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
$payload=Encode-Base64Url ([Text.Encoding]::UTF8.GetBytes('{"iss":"offline","sub":"9007199254740993","name":"synthetic","iat":1,"exp":9999999999}'))
$inputText="$header.$payload"
$original="$inputText.$(Encode-Base64Url (Hmac-Bytes $fixtureSecret $inputText))"
$expired=New-ExpiredJwt $original $fixtureSecret
$parts=$expired -split '[.]'
$decoded=[Text.Encoding]::UTF8.GetString((Decode-Base64Url $parts[1])) | ConvertFrom-Json -AsHashtable
Assert-Offline 'expired JWT preserves exact identity and issuer' ($decoded.sub -ceq '9007199254740993' -and $decoded.iss -ceq 'offline')
Assert-Offline 'expired JWT has a genuinely expired claim' ($decoded.exp -lt [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()-300)
Assert-Offline 'expired JWT remains correctly signed' ($parts[2] -ceq (Encode-Base64Url (Hmac-Bytes $fixtureSecret "$($parts[0]).$($parts[1])")))
$paymentSecret=$fixtureSecret
$callback=New-CallbackHeaders '9007199254740993' 'TEST-trade' 1700000000
$canonical=[string]::Join([char]10,@('9007199254740993','TEST-trade','1700000000'))
Assert-Offline 'callback uses exact id/newline/trade/newline/timestamp canonical bytes' ($callback['X-Payment-Signature'] -ceq [Convert]::ToHexString((Hmac-Bytes $fixtureSecret $canonical)).ToLowerInvariant())
Assert-Offline 'callback timestamp header is the signed timestamp' ($callback['X-Payment-Timestamp'] -ceq '1700000000')

$script:lastSql=''
function Read-Fact([string]$Sql){
    $script:lastSql=$Sql
    return '{"orderId":"9007199254740993","userId":"9","status":2,"reservation":1,"tradeNo":"TEST-trade","events":1,"sentEvents":1,"lockRows":1,"lockedUnits":0,"releasedUnits":0,"confirmedUnits":1}'
}
$fact=Order-Fact '9007199254740993' '9'
Assert-Offline 'read facts constrain exact order and owner' ($lastSql.Contains('o.order_id=9007199254740993 AND o.user_id=9'))
Assert-Offline 'facts quote reserved order table correctly' ($lastSql.Contains(('fulfillment_order.'+[char]96+'order'+[char]96)))
Assert-Offline 'all fixture SQL is SELECT only' ($lastSql -match '^SELECT\b' -and $lastSql -notmatch '(?i)\b(UPDATE|INSERT|DELETE|ALTER|CALL|TRUNCATE)\b')
$prior=$lastSql
$rejected=$false
try{$null=Order-Fact "1'; DELETE FROM anything" '9'}catch{$rejected=$true}
Assert-Offline 'untrusted IDs rejected before SQL' ($rejected -and $lastSql -ceq $prior)
$SettleTimeoutSeconds=10
$fixture=@{orderId='9007199254740993';userId='9';final=$null}
Wait-Settled $fixture $true
Assert-Offline 'paid final fact verifies sent event and confirmed unit' ($fixture.final.confirmedUnits -eq 1 -and $fixture.final.sentEvents -eq 1)

$script:captured=$null
function Invoke-WebRequest {
    param($Uri,$Method,$Headers,$SkipHttpErrorCheck,$NoProxy,$MaximumRedirection,$TimeoutSec,$Verbose,$Debug,$ContentType,$Body)
    $script:captured=@{uri=$Uri;headers=$Headers;body=$Body;redirects=$MaximumRedirection;noProxy=$NoProxy}
    return @{StatusCode=401;Content=''}
}
$empty=Http 'offline-empty-401' 18083 POST '/api/payments/callbacks/success' 401 '' @{orderId='9007199254740993';outTradeNo='TEST-trade'} $callback
Assert-Offline 'empty authentication rejection is valid and not JSON parsed' ($empty.Count -eq 0)
Assert-Offline 'HTTP never follows redirects or external proxies' ($captured.redirects -eq 0 -and $captured.noProxy -eq $true)
Assert-Offline 'callback HTTP preserves exact order string' ($captured.body.Contains('"orderId":"9007199254740993"'))
Assert-Offline 'report assertion has only safe fields' (@($checks[0].Keys | Where-Object {$_ -notin @('name','passed','httpStatus')}).Count -eq 0)
Assert-Offline 'F07 completion gate exists before any registration' ($source.IndexOf("'preflight.F07-completed'") -lt $source.IndexOf("Http 'register-owner'"))
Assert-Offline 'owned listeners checked before container/traffic setup' ($source.IndexOf("    Assert-OwnedListeners") -lt $source.IndexOf('$trafficAllowed=$true'))
Assert-Offline 'no process lifecycle or fault writes' ($source -notmatch '(?im)^\s*(Stop-Process|Start-Process|taskkill|docker\s|Set-Fault)\b')
Assert-Offline 'cleanup never cancels paid orders' ($source.Contains('if($fact.status -eq 1)') -and $source.Contains("result='paid-preserved'"))
Assert-Offline 'failed raw exceptions are suppressed' ($source.Contains('Never serialize or echo them.'))
Write-Output "Offline security contract: $count checks passed; no HTTP, Docker, SQL or process operations performed."
