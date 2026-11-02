param(
    [string]$GameDirectory = 'D:\Games\Hytale\install\release\package\game\latest'
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$testRoot = Join-Path $taskRoot '.runtime\connection-checks'
$classes = Join-Path $testRoot 'classes'
$mods = Join-Path $testRoot 'mods'
New-Item -ItemType Directory -Path $classes,$mods -Force | Out-Null
$serverJar = Join-Path $GameDirectory 'Server\HytaleServer.jar'
$testSource = Join-Path $PSScriptRoot 'integration\ConnectionVerificationPlugin.java'
$runtimeJar=Join-Path $taskRoot 'dist\advanced-storage-0.1.0.jar'
& javac -encoding UTF-8 -cp "$serverJar;$runtimeJar" -d $classes $testSource (Join-Path $PSScriptRoot 'integration\StorageVerification.java')
if ($LASTEXITCODE -ne 0) { throw 'Connection verifier compilation failed.' }
$testManifest = @{
    Group='AdvancedStorageTests';Name='ConnectionVerifier';Version='1.0.0'
    Main='ConnectionVerificationPlugin';ServerVersion='=0.7.5'
    IncludesAssetPack=$false;DisabledByDefault=$false
} | ConvertTo-Json
[System.IO.File]::WriteAllText((Join-Path $classes 'manifest.json'), $testManifest, [System.Text.UTF8Encoding]::new($false))
& jar --create --file (Join-Path $mods 'connection-verifier.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Connection verifier packaging failed.' }
foreach($legacyName in @('advanced-storage-0.1.0.zip','advanced-storage-runtime-0.1.0.jar')) {
    $legacyPath=Join-Path $mods $legacyName
    if(Test-Path -LiteralPath $legacyPath){Remove-Item -LiteralPath $legacyPath}
}
Copy-Item -LiteralPath $runtimeJar -Destination (Join-Path $mods 'advanced-storage-0.1.0.jar') -Force
$assets = Join-Path $GameDirectory 'Assets.zip'
Push-Location $testRoot
try {
    if (Test-Path -LiteralPath 'connection-check-result.txt') {
        Remove-Item -LiteralPath 'connection-check-result.txt'
    }
    if(Test-Path -LiteralPath 'storage-check-result.txt') {Remove-Item -LiteralPath 'storage-check-result.txt'}
    $ErrorActionPreference = 'Continue'
    & java -Xmx4G -jar $serverJar --assets $assets --disable-sentry --auth-mode offline --bind 127.0.0.1:5529 --log INFO --boot-command as-verify-connections --boot-command as-verify-storage --boot-command stop *> 'connection-checks.log'
    $serverExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($serverExit -ne 0) { throw "Test server failed: $serverExit" }
    if (!(Test-Path -LiteralPath 'connection-check-result.txt') -or (Get-Content -LiteralPath 'connection-check-result.txt' -Raw).Trim() -ne 'PASS') {
        throw "Connection checks failed. Read $testRoot\connection-checks.log"
    }
    Select-String -LiteralPath 'connection-checks.log' -Pattern 'ADVANCED_STORAGE_CONNECTION_CHECKS_PASS'
    if(!(Test-Path -LiteralPath 'storage-check-result.txt') -or (Get-Content -LiteralPath 'storage-check-result.txt' -Raw).Trim() -ne 'PASS') {throw 'Functional storage checks failed. See connection-checks.log'}
    Select-String -LiteralPath 'connection-checks.log' -Pattern 'ADVANCED_STORAGE_FUNCTIONAL_CHECKS_PASS'
} finally { Pop-Location }
