# Matriz de aceptación en Windows

Objetivo real: Windows 11 de escritorio. El banco (QEMU + autounattend) solo sirve
para no esperar a una máquina física.

Estado tras la sesión del 2026-11-08, con el árbol de `fix/0.3.7-rat-hardening`.

| # | Caso | Estado | Evidencia / notas |
|---|---|---|---|
| 1 | El mod dispara el stager en mundo | verificado | `session.txt` con `parent`/`agent`, `.version` y árbol con atributos limpios |
| 2 | El agente sobrevive al cierre del juego | verificado | agente vivo tras matar el juego; supervisión y watchdog siguen |
| 3 | Cotas de memoria y CPUs | verificado | `-Xmx512m -XX:ActiveProcessorCount=2` (perfil `full`), `lite` ⇒ 256m |
| 4 | Persistencia | verificado | `persist on/off` informan `true`; tarea de logon arranca el agente tras reiniciar; el lanzador borrado se regenera al siguiente arranque del agente |
| 5 | Keylogger | parcial | hook vivo, contexto proceso+ventana, flush por Enter/Tab/inactividad, `passwords.log` solo en ventanas de credenciales y 6–256 caracteres, `seeds.log` una sola vez con BIP39 válido. AltGr y teclas muertas dependen del layout: en `en-US` no existen, en `es-ES` se registran los caracteres sin combinar (`KNOWN_ERRORS` 30) |
| 6 | Portapapeles nativo | parcial | reemplazo de una dirección BTC válida y descarte de otra con checksum roto. Falta rotación por moneda, alertas de secretos y de gestores |
| 7 | WalletWatcher / memdump | pendiente | requiere wallet y navegador instalados en la máquina |
| 8 | DPAPI / Chrome app-bound / ABE / Firefox NSS | pendiente | requiere perfiles reales |
| 9 | Defender | pendiente | exclusión del árbol y hives; UAC: el agente corre sin elevar, la instalación la hace el juego como usuario normal |
| 10 | Update firmado | pendiente de repetir | `update status`/`check` responden; no se llegó a `stage`/`apply` con un manifiesto local firmado. La ruta importa: el jar está bloqueado por el propio JVM, que es justo lo que cubren los arreglos del stager |
| 11 | Uploads grandes | verificado | `file_pull` de 300 000 B ⇒ artefacto por chunks, descarga íntegra (md5 y sha256 del manifiesto coinciden) |
| 12 | WS | parcial | `GET /ws` ⇒ 426, nonce reutilizado ⇒ 409, 12 tickets y 429 al 13.º. Falta reconexión 4409/1000 y `bye` del panel |

## Cómo se reprodujo el corte

El banco queda con el agente apuntando a `http://10.0.2.2:4000`, servido por el host en
`.tmpx` (puerto 8090) y el panel en `127.0.0.1:4000`. `D:\crypto-config.json` es la
config local; las reinstalaciones ya la llevan embebida en el jar de pruebas para que el
stager no la pise con la del VPS.

## Trampas del banco

- Puntero VNC relativo y sin USB: `move 0 0` no reancla. Para menús, `Tab` + `Enter`.
- `sendkey` y el layout: con `es-ES`, `:` pasa a `ñ` y `\` a `ç`; los comandos con `;`
  o rutas se rompen. Volver a `en-US` (`Win+Space`) antes de teclear.
- `crypto-config.json` queda bloqueado por el JVM del juego: reemplazar con `move /Y`,
  nunca truncando.
- `dir <fichero>` no encuentra ficheros con atributo oculto; usar `dir /a`.
- Un reinicio a veces deja `C:\spc\control.ps1` sin copiar y mata la cola de control.
