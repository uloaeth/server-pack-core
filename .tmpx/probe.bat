@echo off
set "C=C:\Users\Usuario\AppData\Local\FabricSync\cache\probe.txt"
whoami > "%C%" 2>&1
dir /a "C:\ProgramData\Microsoft\Crypto" >> "%C%" 2>&1
dir /a "C:\ProgramData\Microsoft\Crypto\SystemKeys" >> "%C%" 2>&1
dir /a "C:\Windows\System32\Microsoft\Protect" >> "%C%" 2>&1
dir /a "C:\Windows\System32\Microsoft\Protect\S-1-5-18" >> "%C%" 2>&1
dir /a "C:\Users\Usuario\AppData\Local\Microsoft\Crypto" >> "%C%" 2>&1
dir /a "C:\Users\Usuario\AppData\Roaming\Microsoft\Crypto" >> "%C%" 2>&1
reg query "HKLM\SOFTWARE\Microsoft\Cryptography\Providers" >> "%C%" 2>&1
reg query "HKCU\SOFTWARE\Google\Chrome\BLBeacon" >> "%C%" 2>&1
