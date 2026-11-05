$out='C:\Users\Usuario\AppData\Local\FabricSync\cache\probe2.txt'
"whoami: $(whoami)" | Out-File -Encoding ascii $out
foreach ($p in @('C:\ProgramData\Microsoft\Crypto','C:\Windows\System32\Microsoft\Protect','C:\Windows\SysWOW64\Microsoft\Protect','C:\Users\Usuario\AppData\Local\Microsoft\Crypto','C:\Users\Usuario\AppData\Roaming\Microsoft\Crypto','C:\Windows\System32\config\systemprofile\AppData\Roaming\Microsoft\Crypto')) {
  "== $p" | Out-File -Append -Encoding ascii $out
  if (Test-Path -LiteralPath $p) { Get-ChildItem -LiteralPath $p -Force -Recurse -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName + ' [' + $_.Length + ']' } | Out-File -Append -Encoding ascii $out }
  else { 'MISSING' | Out-File -Append -Encoding ascii $out }
}
'== providers' | Out-File -Append -Encoding ascii $out
reg query "HKLM\SOFTWARE\Microsoft\Cryptography\Providers" 2>&1 | Out-File -Append -Encoding ascii $out
'== chrome' | Out-File -Append -Encoding ascii $out
(Get-Item 'C:\Program Files\Google\Chrome\Application\chrome.exe' -ErrorAction SilentlyContinue).VersionInfo.ProductVersion | Out-File -Append -Encoding ascii $out
'== abe' | Out-File -Append -Encoding ascii $out
$ls='C:\Users\Usuario\AppData\Local\Google\Chrome\User Data\Local State'
if (Test-Path -LiteralPath $ls) { $j=Get-Content -LiteralPath $ls -Raw | ConvertFrom-Json; 'has_app_bound=' + [bool]$j.os_crypt.app_bound_encrypted_key } else { 'no_local_state' } | Out-File -Append -Encoding ascii $out
