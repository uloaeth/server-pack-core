param([Parameter(Mandatory = $true)][int]$ParentPid)
$ErrorActionPreference = 'SilentlyContinue'
# The agent reads UTF-8; titles can carry accents.
try { [Console]::OutputEncoding = New-Object -TypeName System.Text.UTF8Encoding -ArgumentList $false } catch { }

Add-Type @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class SpcWallet {
    [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(
        IntPtr window, StringBuilder text, int size);
    [DllImport("user32.dll")] public static extern bool EnumChildWindows(
        IntPtr parent, EnumProc callback, IntPtr param);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(
        IntPtr window, StringBuilder text, int size);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr window, out Rect rect);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr window);
    public delegate bool EnumProc(IntPtr window, IntPtr param);
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
    /**
     * Chrome renders an extension popup as a narrow visible child window, but
     * leaves its title empty, so the title alone never names the wallet there.
     * The popup is a RenderWidgetHost well below the browser width.
     */
    public static bool HasPopup() {
        IntPtr foreground = GetForegroundWindow();
        StringBuilder cls = new StringBuilder(256);
        GetClassName(foreground, cls, cls.Capacity);
        if (cls.ToString() != "Chrome_WidgetWin_1") { return false; }
        Rect window;
        GetWindowRect(foreground, out window);
        int windowWidth = window.Right - window.Left;
        bool found = false;
        EnumChildWindows(foreground, delegate(IntPtr child, IntPtr param) {
            StringBuilder childCls = new StringBuilder(256);
            GetClassName(child, childCls, childCls.Capacity);
            if (childCls.ToString() != "Chrome_RenderWidgetHostHWND" || !IsWindowVisible(child)) {
                return true;
            }
            Rect rect;
            GetWindowRect(child, out rect);
            int width = rect.Right - rect.Left;
            if (width >= 180 && width <= 460 && width <= windowWidth - 200
                && rect.Bottom - rect.Top >= 250) {
                found = true;
            }
            return true;
        }, IntPtr.Zero);
        return found;
    }
}
'@

function Start-Ticks([int]$processId) {
    try { return (Get-Process -Id $processId).StartTime.Ticks } catch { return 0 }
}

# Known wallet extension ids, so a title-less popup still gets a name.
$script:walletIds = [ordered]@{
    'bfnaelmomeimhlpmgjnjophhpkkoljpa' = 'phantom'
    'nkbihfbeogaeaoehlefnkodbefgpgknn' = 'metamask'
    'acmacodkjbdgmoleebolmdjonilkdbch' = 'rabby'
    'egjidjbpglichdcondbcbdnbeeppgdph' = 'trust'
    'hnifcgeiblffmjmiibhbbbfnaoepehno' = 'coinbase'
}
$script:popupWallet = $null
$script:popupAt = 0

function Get-PopupWallet() {
    if ($null -ne $script:popupWallet -and
        ([Environment]::TickCount64 - $script:popupAt) -lt 60000) {
        return $script:popupWallet
    }
    $found = @()
    $roots = @(
        (Join-Path $env:LOCALAPPDATA 'Google\Chrome\User Data'),
        (Join-Path $env:LOCALAPPDATA 'Microsoft\Edge\User Data'),
        (Join-Path $env:LOCALAPPDATA 'BraveSoftware\Brave-Browser\User Data'))
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        foreach ($profile in (Get-ChildItem $root -Directory)) {
            $extensions = Join-Path $profile.FullName 'Extensions'
            if (-not (Test-Path $extensions)) { continue }
            foreach ($id in $script:walletIds.Keys) {
                if ((Test-Path (Join-Path $extensions $id)) -and
                    $found -notcontains $script:walletIds[$id]) {
                    $found += $script:walletIds[$id]
                }
            }
        }
    }
    $value = ''
    if ($found -contains 'phantom') { $value = 'phantom' }
    elseif ($found.Count -gt 0) { $value = $found[0] }
    $script:popupWallet = $value
    $script:popupAt = [Environment]::TickCount64
    return $value
}

# Extension popups run in a renderer/content process whose PID changes between
# openings. Walk up to the browser main process so the dedupe key stays stable.
function Get-BrowserRoot([int]$processId) {
    $current = $processId
    for ($depth = 0; $depth -lt 8; $depth++) {
        $info = Get-CimInstance Win32_Process -Filter "ProcessId = $current"
        if (-not $info) { break }
        if ($info.Name -match '(?i)^(chrome|msedge|brave|firefox|librewolf|waterfox)(\.exe)?$' -and
            $info.CommandLine -notmatch '(?i)-contentproc|--type=(renderer|gpu|utility|extension)') {
            return $current
        }
        $parent = [int]$info.ParentProcessId
        if ($parent -le 0 -or $parent -eq $current) { break }
        $current = $parent
    }
    return $processId
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
# process is really gone or the grace expires. The reason tells the agent
# whether a live PID may keep the session: `dead` always clears, `grace` only
# closes browser extensions (their PID survives the popup) and keeps a desktop
# wallet that merely lost focus. A browser key is released after grace so a
# reopen is detected again; a desktop key is kept so a real exit is reported.
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

    if ($tick % 4 -eq 0) {
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
    } elseif ($foreground -match '(?i)chrome|msedge|brave|firefox|librewolf|waterfox') {
        if ($title -match '(?i)phantom') { $name = 'phantom'; $target = $processId }
        elseif ($title -match '(?i)metamask') { $name = 'metamask'; $target = $processId }
        elseif ($title -match '(?i)rabby') { $name = 'rabby'; $target = $processId }
        elseif ($title -match '(?i)trust wallet') { $name = 'trust'; $target = $processId }
        elseif ($title -match '(?i)coinbase wallet') { $name = 'coinbase'; $target = $processId }
        elseif ([SpcWallet]::HasPopup()) {
            # Chrome extension popups have no title: fall back to the installed
            # wallet extension when one is open on an unrelated page.
            $popup = Get-PopupWallet
            if ($popup -ne '') { $name = $popup; $target = $processId }
        }
    }

    if ($name -ne '' -and $foreground -match '(?i)chrome|msedge|brave|firefox|librewolf|waterfox') {
        $target = Get-BrowserRoot $processId
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
        $start = Start-Ticks $target
        $key = "$name|$target|$start"
        if ($key -ne $last) {
            # A new key while the old process lives is a switch (another wallet
            # or another renderer), not a close. Only a gone process is `dead`.
            if ($last -ne '') {
                $previousAlive = Get-Process -Id $lastPid -ErrorAction SilentlyContinue
                if (-not $previousAlive) {
                    [Console]::Out.WriteLine("LOST|$lastName|dead")
                    [Console]::Out.Flush()
                }
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
            [Console]::Out.WriteLine("LOST|$lastName|dead")
            [Console]::Out.Flush()
            $last = ''
            $lastName = ''
            $lastPid = 0
            $lostReported = $false
        } elseif (-not $lostReported -and $age -gt $graceMs) {
            [Console]::Out.WriteLine("LOST|$lastName|grace")
            [Console]::Out.Flush()
            $lostReported = $true
            # A browser popup that reopens must produce a fresh DETECT so the
            # agent can restore the session; the grace already closed it, so a
            # later real exit needs no second LOST. Desktop wallets keep the
            # key to tell a focus bounce apart from a close.
            if ($lastName -match '(?i)^(phantom|metamask|rabby|trust|coinbase)$') {
                $last = ''
                $lastName = ''
                $lastPid = 0
                $lostReported = $false
            }
        }
    }

    Start-Sleep -Milliseconds 2000
}
