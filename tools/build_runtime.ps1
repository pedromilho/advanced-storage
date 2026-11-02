param(
    [string]$GameDirectory = 'D:\Games\Hytale\install\release\package\game\latest',
    [string]$InstallTo = ''
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $taskRoot 'build\runtime-classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
Get-ChildItem -LiteralPath $classes -Filter '*.class' -File -Recurse | ForEach-Object {Remove-Item -LiteralPath $_.FullName}
& python (Join-Path $PSScriptRoot 'build_pack.py')
if($LASTEXITCODE -ne 0){throw 'Asset packaging failed'}
$serverJar=Join-Path $GameDirectory 'Server\HytaleServer.jar'
$sources=Get-ChildItem -LiteralPath (Join-Path $taskRoot 'src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName
& javac -encoding UTF-8 -cp $serverJar -d $classes $sources
if($LASTEXITCODE -ne 0){throw 'Runtime compilation failed'}
Copy-Item -LiteralPath (Join-Path $taskRoot 'config\storage_seals.json') -Destination $classes -Force
if($InstallTo){
    & python (Join-Path $PSScriptRoot 'package_mod.py') --install-to $InstallTo
} else {
    & python (Join-Path $PSScriptRoot 'package_mod.py')
}
if($LASTEXITCODE -ne 0){throw 'Unified mod packaging or installation failed'}
