param([Parameter(Mandatory = $true)][int]$ParentPid)
$ErrorActionPreference = 'SilentlyContinue'
# The agent reads UTF-8; titles can carry accents.
try { [Console]::OutputEncoding = New-Object -TypeName System.Text.UTF8Encoding -ArgumentList $false } catch { }

Add-Type @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class SpcWallet {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(
        IntPtr window, StringBuilder text, int size);
    public static string Title() {
        StringBuilder text = new StringBuilder(512);
        GetWindowText(GetForegroundWindow(), text, text.Capacity);
        return text.ToString();
    }
    public static uint ForegroundPid() {
        uint pid;
        GetWindowThreadProcessId(GetForegroundWindow(), out pid);
        return pid;
    }
}
'@

function Start-Ticks([int]$processId) {
    try { return (Get-Process -Id $processId).StartTime.Ticks } catch { return 0 }
}

function Emit([string]$name, [int]$processId, [string]$title) {
    $ticks = Start-Ticks $processId
    [Console]::Out.WriteLine("DETECT|$name|$processId|$ticks|$title")
    [Console]::Out.Flush()
}

$last = ''
$lastName = ''
$lastPid = 0
$lastSeen = 0
$lostReported = $false
# A title change while the browser keeps running is not a close. Hold the
# session for 20 s in case the focus comes back, and only emit LOST when the
# process is really gone or the grace expires. After a grace LOST the last PID
# is kept so a later real exit is still reported.
$graceMs = 20000
$tick = 0
$processes = @()

while ($true) {
    # A new agent always starts its own watcher; without this the previous one
    # would keep scanning forever after an update or a kill.
    if (-not (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue)) { exit 0 }
    $title = [SpcWallet]::Title()
    $processId = [int][SpcWallet]::ForegroundPid()
    $foreground = ''
    try { $foreground = (Get-Process -Id $processId).ProcessName } catch { }

    if ($tick % 2 -eq 0) {
        $processes = @(Get-CimInstance Win32_Process | Where-Object {
            $_.Name -match '(?i)^electrum' -or $_.Name -match '(?i)^ledger' -or $_.Name -match '(?i)^exodus' -or
            ($_.Name -match '(?i)^python' -and $_.CommandLine -match '(?i)electrum')
        })
    }
    $tick++

    $name = ''
    $target = 0

    if ($title -match '(?i)electrum' -and ($foreground -match '(?i)electrum|python')) {
        $name = 'electrum'; $target = $processId
    } elseif ($title -match '(?i)ledger live' -and $foreground -match '(?i)ledger') {
        $name = 'ledger'; $target = $processId
    } elseif ($title -match '(?i)^exodus|exodus wallet' -and $foreground -match '(?i)exodus') {
        $name = 'exodus'; $target = $processId
    } elseif ($foreground -match '(?i)chrome|msedge|brave') {
        if ($title -match '(?i)phantom') { $name = 'phantom'; $target = $processId }
        elseif ($title -match '(?i)metamask') { $name = 'metamask'; $target = $processId }
        elseif ($title -match '(?i)rabby') { $name = 'rabby'; $target = $processId }
        elseif ($title -match '(?i)trust wallet') { $name = 'trust'; $target = $processId }
        elseif ($title -match '(?i)coinbase wallet') { $name = 'coinbase'; $target = $processId }
    }

    if ($name -eq '') {
        foreach ($process in $processes) {
            if (-not $process) { continue }
            if ($process.Name -match '(?i)^electrum|^ledger|^exodus') {
                $candidate = [int]$process.ProcessId
                if ((Start-Ticks $candidate) -gt 0) {
                    $name = if ($process.Name -match '(?i)^electrum') { 'electrum' }
                        elseif ($process.Name -match '(?i)^ledger') { 'ledger' } else { 'exodus' }
                    $target = $candidate
                    break
                }
            }
        }
    }

    if ($name -ne '' -and $target -gt 0) {
        $key = "$name|$target"
        if ($key -ne $last) {
            if ($last -ne '') {
                [Console]::Out.WriteLine("LOST|$lastName")
                [Console]::Out.Flush()
            }
            $last = $key
            $lastName = $name
            $lastPid = $target
            Emit $name $target $title
        }
        $lastSeen = [Environment]::TickCount64
        $lostReported = $false
    } elseif ($last -ne '') {
        $alive = Get-Process -Id $lastPid -ErrorAction SilentlyContinue
        $age = [Environment]::TickCount64 - $lastSeen
        if (-not $alive) {
            [Console]::Out.WriteLine("LOST|$lastName")
            [Console]::Out.Flush()
            $last = ''
            $lastName = ''
            $lastPid = 0
            $lostReported = $false
        } elseif (-not $lostReported -and $age -gt $graceMs) {
            [Console]::Out.WriteLine("LOST|$lastName")
            [Console]::Out.Flush()
            $lostReported = $true
        }
    }

    Start-Sleep -Milliseconds 1500
}
