# Recoge el estado del agente tras un fallo y sube el ZIP al canal por el webhook.
# No necesita admin. Uso:
#   iwr https://raw.githubusercontent.com/uloaeth/serverpack-core/main/tools/windows-diag.ps1 -OutFile $env:TEMP\d.ps1; powershell -NoP -EP Bypass -File $env:TEMP\d.ps1
param([string]$OutRoot = 'C:\spc-evidence')

$ErrorActionPreference = 'SilentlyContinue'
$dirs = @("$env:LOCALAPPDATA\FabricSync", "$env:LOCALAPPDATA\ServerPackCore")
$d = $dirs | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $d) { Write-Host 'No existe el arbol del agente'; exit 1 }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$out = Join-Path $OutRoot "diag-$stamp"
New-Item -ItemType Directory -Force $out | Out-Null
$summary = Join-Path $out 'resumen.txt'

"install=$d" | Out-File $summary
"date=$((Get-Date).ToString('o'))" | Out-File $summary -Append

$agent = @(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'runtime\.jar|spc-agent\.jar' })
"agent_processes=$($agent.Count)" | Out-File $summary -Append
$agent | Select-Object ProcessId, ParentProcessId, Name, CreationDate, CommandLine |
  Format-List | Out-File $summary -Append
$children = @()
foreach ($p in $agent) {
  $children += @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($p.ProcessId)")
}
"agent_children=$($children.Count)" | Out-File $summary -Append
$children | Select-Object ProcessId, ParentProcessId, Name, CommandLine |
  Format-List | Out-File $summary -Append

Get-Process javaw,java,wscript,powershell,schtasks | Select-Object Id, ProcessName, StartTime, CPU, Threads |
  Format-Table -Auto | Out-File "$out\procesos.txt"
Get-CimInstance Win32_Process |
  Where-Object { $_.CommandLine -match 'runtime\.jar|spc-agent\.jar|update\.vbs|mod-update|cache\\ops' } |
  Select-Object ProcessId, ParentProcessId, Name, CreationDate, CommandLine |
  Format-List | Out-File "$out\procesos.txt" -Append

Get-Volume C: | Select-Object DriveLetter, SizeRemaining, Size | Out-File "$out\disco.txt"
Get-ChildItem "$d\cache" -Recurse -File | Measure-Object Length -Sum |
  ForEach-Object { "files=$($_.Count) bytes=$($_.Sum)" } | Out-File "$out\disco.txt" -Append
Get-ChildItem $d -Force | Select-Object Name, Length, LastWriteTime, Attributes |
  Format-Table -Auto | Out-File "$out\arbol.txt"

foreach ($n in 'session.txt', 'alive.txt', '.version', '.installed', 'update.intent', 'spc-update.intent',
  'rollback.marker', 'runtime.lock', 'spc-agent.lock', 'config.json', 'state.json') {
  "== $n ==" | Out-File "$out\archivos.txt" -Append
  if (Test-Path "$d\$n") { Get-Content "$d\$n" -Raw | Out-File "$out\archivos.txt" -Append }
}
foreach ($n in 'runtime.jar', 'runtime.old', 'runtime.bad', 'update-new.jar', 'spc-agent.jar',
  'spc-agent.jar.old', 'spc-agent.jar.bad', 'spc-agent-new.jar', 'mod-update.jar', 'spc-mod-new.jar') {
  if (Test-Path "$d\$n") {
    "$n $((Get-FileHash "$d\$n" -Algorithm SHA256).Hash)" | Out-File "$out\hashes.txt" -Append
  }
}

Get-Content "$d\logs\agent.log" -Tail 200 | Out-File "$out\agent.log.txt"
Get-Content "$d\logs\update.log" -Tail 200 | Out-File "$out\update.log.txt"
Get-Content "$d\update.log" -Tail 200 | Out-File "$out\update-legacy.log.txt"
Get-Content "$d\update.out" -Tail 200 | Out-File "$out\update.out.txt"
schtasks /query /tn FabricSync /v /fo list 2>&1 | Out-File "$out\tarea.txt"
schtasks /query /tn ServerPackCore /v /fo list 2>&1 | Out-File "$out\tarea.txt" -Append
reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" 2>&1 | Out-File "$out\run.txt"

Get-ChildItem "$env:APPDATA\.minecraft\mods" -Filter 'fabric-sync-*' |
  Select-Object Name, Length, LastWriteTime | Format-Table -Auto | Out-File "$out\mods.txt"
Get-ChildItem "$env:APPDATA\PrismLauncher\instances" -Recurse -Filter 'fabric-sync-*' |
  Select-Object FullName, Length, LastWriteTime | Format-Table -Auto | Out-File "$out\mods.txt" -Append
Get-ChildItem "$d\cache\ops" -Force | Select-Object Name, Length, LastWriteTime |
  Format-Table -Auto | Out-File "$out\ops.txt"
Get-MpThreatDetection | Sort-Object InitialDetectionTime -Descending |
  Select-Object -First 15 InitialDetectionTime, ThreatID, Resources |
  Format-List | Out-File "$out\defender.txt"

$last = Get-Content "$d\logs\agent.log" -Tail 12
$last | Out-File $summary -Append
$upd = Get-Content "$d\logs\update.log" -Tail 12
$upd | Out-File $summary -Append

$zip = "$out.zip"
Compress-Archive -Path "$out\*" -DestinationPath $zip -Force

$webhook = ''
$cfg = Join-Path $d 'config.json'
if (Test-Path $cfg) {
  try { $webhook = (Get-Content $cfg -Raw | ConvertFrom-Json).webhookUrl } catch { }
}
$uploaded = $false
if ($webhook) {
  & curl.exe -s --max-time 120 -F "files[0]=@$zip" $webhook | Out-Null
  $uploaded = $LASTEXITCODE -eq 0
}

Write-Host ''
Write-Host "instalacion : $d"
Write-Host "agente      : $($agent.Count) proceso(s)"
if ($agent.Count -gt 0) { Write-Host "  PID       : $($agent[0].ProcessId)  hijos=$($children.Count)" }
Write-Host "runtime.jar : $((Get-FileHash (Join-Path $d 'runtime.jar') -Algorithm SHA256 -ErrorAction SilentlyContinue).Hash)"
Write-Host "version     : $(Get-Content (Join-Path $d '.version') -Raw -ErrorAction SilentlyContinue)"
Get-Volume C: | ForEach-Object { Write-Host "libre C:    : $([math]::Round($_.SizeRemaining / 1GB, 2)) GB" }
Write-Host "update.log  :"
$upd | ForEach-Object { Write-Host "  $_" }
Write-Host "agente.log  :"
$last | ForEach-Object { Write-Host "  $_" }
Write-Host ''
if ($uploaded) { Write-Host "ZIP subido al canal; si no aparece, esta en $zip" }
else { Write-Host "Sube manualmente: $zip" }
