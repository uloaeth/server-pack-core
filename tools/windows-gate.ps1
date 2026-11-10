param(
  [Parameter(Mandatory = $true)]
  [ValidateSet('outbox-cross-volume','atomic-fallback','powershell-blocked','uninstall','hs-err','manifest','stager-version')]
  [string]$Scenario
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$work = Join-Path $env:RUNNER_TEMP "spc-gate-$Scenario"
New-Item -ItemType Directory -Force -Path $work | Out-Null

function Run-Gate([string]$name, [string]$dir) {
  Write-Host "== gate $name ($dir)"
  & "$root\gradlew.bat" -p "$root\agent" windowsGate --no-daemon -q "-Pgate=$name" "-PgateDir=$dir"
  if ($LASTEXITCODE -ne 0) { throw "gate $name failed with $LASTEXITCODE" }
}

# A VHD gives a genuinely different volume; subst is the fallback when the
# runner cannot mount disks.
function Mount-OtherVolume {
  $letter = 'S'
  $vhd = Join-Path $work 'gate.vhdx'
  try {
    $disk = @"
create vdisk file="$vhd" maximum=512 type=expandable
select vdisk file="$vhd"
attach vdisk
create partition primary
format fs=ntfs quick label=spcgate
assign letter=$letter
"@
    $disk | diskpart | Out-Null
    if (Test-Path "${letter}:\") { return "${letter}:\spc-gate" }
  } catch {
    Write-Warning "VHD mount failed: $_"
  }
  cmd /c "subst $letter`: $work" | Out-Null
  return "${letter}:\spc-gate"
}

switch ($Scenario) {
  'outbox-cross-volume' {
    $dir = Mount-OtherVolume
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Run-Gate 'outbox-cross-volume' $dir
  }
  'atomic-fallback' {
    $dir = Join-Path $work 'same-volume'
    Run-Gate 'atomic-fallback' $dir
  }
  'powershell-blocked' {
    # Constrained Language Mode: CIM must return empty, never throw.
    # SPC_AGENT is optional so the script also works outside CI.
    $env:SPC_AGENT = if ($env:SPC_AGENT) { $env:SPC_AGENT } else { Join-Path $root 'agent' }
    # Resolve the path outside the constrained shell: under CLM the nested
    # `$env:` expansion was reaching Java as a literal.
    $gateDir = Join-Path $env:RUNNER_TEMP 'spc-gate-clm'
    $gateScript = @'
$ExecutionContext.SessionState.LanguageMode = 'ConstrainedLanguage'
Set-Location $env:SPC_AGENT
& .\..\gradlew.bat windowsGate --no-daemon -q -Pgate=cim "-PgateDir={0}"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
'@ -f $gateDir
    powershell -NoProfile -Command $gateScript
    if ($LASTEXITCODE -ne 0) { throw 'CLM gate failed' }
  }
  'uninstall' {
    Run-Gate 'uninstall' (Join-Path $work 'install')
  }
  'hs-err' {
    Run-Gate 'hs-err' $work
  }
  'manifest' {
    Run-Gate 'manifest' $work
  }
  'stager-version' {
    Run-Gate 'stager-version' $work
  }
}
