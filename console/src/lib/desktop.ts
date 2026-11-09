/**
 * Puente mínimo con el contenedor Tauri. En el navegador todas las funciones
 * son no-op: la web sigue funcionando sin cargar el SDK de escritorio.
 */
type TauriInternals = {
  invoke<T>(command: string, args?: Record<string, unknown>): Promise<T>;
};

function internals(): TauriInternals | null {
  if (typeof window === 'undefined') return null;
  return (window as unknown as { __TAURI_INTERNALS__?: TauriInternals }).__TAURI_INTERNALS__ ?? null;
}

export function isDesktop(): boolean {
  return internals() !== null;
}

async function call<T>(command: string, args?: Record<string, unknown>): Promise<T | null> {
  const bridge = internals();
  if (!bridge) return null;
  try {
    return await bridge.invoke<T>(command, args);
  } catch {
    return null;
  }
}

export async function setDesktopTitle(title: string): Promise<void> {
  await call('set_title', { title });
}

export async function setTrayStatus(online: boolean, count: number): Promise<void> {
  await call('set_tray_status', { online, count });
}

export async function desktopNotify(title: string, body: string): Promise<void> {
  await call('desktop_notify', { title, body });
}

export async function openServerSettings(): Promise<void> {
  await call('open_server_settings');
}

export async function openExternal(url: string): Promise<void> {
  if (!isDesktop()) {
    window.open(url, '_blank', 'noopener,noreferrer');
    return;
  }
  await call('open_external', { url });
}

export function onDesktopCommand(name: 'shortcuts' | 'about', handler: () => void): () => void {
  if (typeof window === 'undefined') return () => undefined;
  const event = `spc:${name}`;
  const listener = () => handler();
  window.addEventListener(event, listener);
  return () => window.removeEventListener(event, listener);
}
