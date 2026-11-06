param([string]$Target, [string]$OutList)

$ErrorActionPreference = 'SilentlyContinue'
Add-Type @"
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
"@

$candidates = @()
if ($Target -match '^\d+$') {
    $candidates = @(Get-Process -Id ([int]$Target))
} else {
    $filtered = Get-CimInstance Win32_Process | Where-Object {
        if ($Target -eq 'electrum') {
            $_.CommandLine -match 'electrum' -or $_.Name -match '^electrum'
        } elseif ($Target -eq 'phantom') {
            $_.CommandLine -match '--extension-process'
        } else {
            $_.Name -match $Target
        }
    }
    foreach ($item in $filtered) {
        $process = Get-Process -Id $item.ProcessId
        if ($process) { $candidates += $process }
    }
}

$seen = @{}
$count = 0
foreach ($process in ($candidates | Sort-Object WorkingSet64 -Descending)) {
    if ($seen.ContainsKey($process.Id)) { continue }
    $seen[$process.Id] = 1
    if ($count -ge 4) { break }
    $count++

    $path = Join-Path $env:TEMP ("spc-$($process.Id)-$(Get-Date -Format yyyyMMddHHmmss).dmp")
    $handle = [SpcDump]::OpenProcess(0x0410, $false, [uint32]$process.Id)
    if ($handle -eq [IntPtr]::Zero) { continue }
    $stream = [IO.File]::Create($path)
    $ok = [SpcDump]::MiniDumpWriteDump($handle, [uint32]$process.Id,
        $stream.SafeFileHandle.DangerousGetHandle(), 2, [IntPtr]::Zero, [IntPtr]::Zero, [IntPtr]::Zero)
    $stream.Close()
    [SpcDump]::CloseHandle($handle)
    if ($ok) { Add-Content -LiteralPath $OutList -Value $path }
}
