import { after, before, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import * as React from 'react';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { JSDOM } from 'jsdom';
import type { Victim } from '../src/lib/types';

let render: typeof import('@testing-library/react').render;
let screen: typeof import('@testing-library/react').screen;
let fireEvent: typeof import('@testing-library/react').fireEvent;
let cleanup: typeof import('@testing-library/react').cleanup;
let Table: typeof import('../src/components/victim-table').VictimTable;
let Menu: typeof import('../src/components/action-menu').ActionMenu;
let ModuleMenu: typeof import('../src/components/module-menu').ModuleMenu;
let ResultDialog: typeof import('../src/components/module-result-dialog').ModuleResultDialog;
let Bulk: typeof import('../src/components/bulk-dialog').BulkPreview;
let Status: typeof import('../src/components/ui/table');
let useHotkeys: typeof import('../src/hooks/useHotkeys').useHotkeys;
let actionReason: typeof import('../src/lib/actions').actionReason;
let ACTION_MAP: typeof import('../src/lib/actions').ACTION_MAP;

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/victims' });

before(async () => {
  const view = dom.window as unknown as Record<string, unknown>;
  for (const key of ['window', 'document', 'navigator', 'HTMLElement', 'HTMLInputElement', 'Node', 'Element', 'Event', 'KeyboardEvent', 'MouseEvent', 'localStorage', 'getComputedStyle', 'MutationObserver']) {
    Object.defineProperty(globalThis, key, { value: view[key], configurable: true, writable: true });
  }
  (globalThis as unknown as Record<string, unknown>).React = React;
  (globalThis as unknown as { document: Document }).document.dispatchEvent = () => true;
  (globalThis as unknown as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;
  const testing = await import('@testing-library/react');
  render = testing.render;
  screen = testing.screen;
  fireEvent = testing.fireEvent;
  cleanup = testing.cleanup;
  Table = (await import('../src/components/victim-table')).VictimTable;
  Menu = (await import('../src/components/action-menu')).ActionMenu;
  ModuleMenu = (await import('../src/components/module-menu')).ModuleMenu;
  ResultDialog = (await import('../src/components/module-result-dialog')).ModuleResultDialog;
  Bulk = (await import('../src/components/bulk-dialog')).BulkPreview;
  Status = await import('../src/components/ui/table');
  useHotkeys = (await import('../src/hooks/useHotkeys')).useHotkeys;
  ({ actionReason, ACTION_MAP } = await import('../src/lib/actions'));
});

after(() => cleanup());
beforeEach(() => cleanup());

const victim = (id: string, hostname: string, overrides: Partial<Victim> = {}): Victim => ({
  id,
  hostname,
  username: 'ana',
  os: 'windows',
  osVersion: '10.0.19045',
  arch: 'x86_64',
  country: 'España',
  countryCode: 'ES',
  city: 'Madrid',
  lat: null,
  lon: null,
  tags: ['lab'],
  status: 'online',
  profile: 'full',
  agentVersion: '0.3.7',
  ip: '10.0.0.5',
  firstSeenAt: new Date().toISOString(),
  lastHeartbeatAt: new Date().toISOString(),
  online: true,
  socket: true,
  artifacts: 3,
  ...overrides
});

test('la tabla pinta columnas densas y conserva el estado vacío', () => {
  const first = render(createElement(Table, { items: [victim('a', 'ALFA')], loading: false, selected: [], sort: { id: 'seen', dir: 'desc' }, onSelect: () => undefined, onSort: () => undefined, onOpen: () => undefined, onMenu: () => undefined, onBulk: () => undefined }));
  assert.ok(screen.getByText('ALFA'));
  assert.ok(screen.getByText('Usuario'));
  assert.ok(screen.getByText('Socket'));
  first.unmount();

  render(createElement(Table, { items: [], loading: false, selected: [], sort: { id: 'seen', dir: 'desc' }, onSelect: () => undefined, onSort: () => undefined, onOpen: () => undefined, onMenu: () => undefined, onBulk: () => undefined }));
  assert.ok(screen.getByText('Ningún equipo coincide'));
});

test('el menú agrupa acciones y deshabilita las que necesitan socket', () => {
  const offline = victim('a', 'ALFA', { socket: false });
  let picked = '';
  const view = render(createElement(Menu, {
    victim: offline,
    x: 10,
    y: 10,
    context: { canWrite: true, offline: true, disconnected: false, windows: true },
    onPick: (action) => { picked = action.id; },
    onClose: () => undefined
  }));
  assert.ok(screen.getByText('Energía'));
  assert.ok(screen.getByText('Credenciales'));
  const open = screen.getByRole('menuitem', { name: /Abrir detalle/ });
  assert.equal(open.getAttribute('aria-disabled'), 'false');
  const power = screen.getByRole('menuitem', { name: /^Apagar shell/ });
  assert.equal(power.getAttribute('aria-disabled'), 'true');
  fireEvent.click(open);
  assert.equal(picked, 'open');
  view.unmount();
});

test('el menú responde a flechas y Enter sin ratón', () => {
  const item = victim('a', 'ALFA');
  let picked = '';
  const view = render(createElement(Menu, {
    victim: item,
    x: 10,
    y: 10,
    context: { canWrite: true, offline: false, disconnected: false, windows: true },
    onPick: (action) => { picked = action.id; },
    onClose: () => undefined
  }));
  const menu = screen.getByRole('menu');
  fireEvent.keyDown(menu, { key: 'ArrowDown' });
  fireEvent.keyDown(menu, { key: 'Enter' });
  assert.ok(picked);
  assert.notEqual(picked, 'open');
  view.unmount();
});

test('las tareas nuevas exigen agente 0.3.7', () => {
  const old = victim('a', 'ALFA', { agentVersion: '0.3.6' });
  const context = { canWrite: true, offline: false, disconnected: false, windows: true, agentVersion: '0.3.6' };
  assert.equal(actionReason(ACTION_MAP.get('monitor')!, old, context), 'requiere agente 0.3.7');
  assert.equal(actionReason(ACTION_MAP.get('harvest')!, old, context), null);
  assert.equal(actionReason(ACTION_MAP.get('monitor')!, old, { ...context, agentVersion: '0.3.7' }), null);
});

test('el lote confirma número y equipos antes de ejecutar', () => {
  const view = render(createElement(Bulk, {
    victims: [victim('a', 'ALFA'), victim('b', 'BRAVO')],
    onRun: () => undefined
  }));
  assert.ok(screen.getByText(/2 equipos seleccionados/));
  assert.ok(screen.getByText('ALFA'));
  assert.ok(screen.getByText('BRAVO'));
  assert.ok(screen.getByRole('button', { name: /Ejecutar en 2/ }));
  view.unmount();
});

test('los atajos / y Ctrl+K respetan el foco de escritura', () => {
  const calls: string[] = [];
  function Probe() {
    useHotkeys({ '/': () => calls.push('slash'), 'mod+k': () => calls.push('palette') });
    return createElement('input', { id: 'probe' });
  }
  render(createElement(Probe));
  fireEvent.keyDown(window, { key: '/' });
  fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
  assert.deepEqual(calls, ['slash', 'palette']);
  const input = screen.getByRole('textbox');
  fireEvent.keyDown(input, { key: '/' });
  assert.deepEqual(calls, ['slash', 'palette']);
  fireEvent.keyDown(input, { key: 'k', ctrlKey: true });
  assert.deepEqual(calls, ['slash', 'palette', 'palette']);
});

test('error y vacío ofrecen reintento', () => {
  let retried = 0;
  const view = render(createElement(Status.ErrorState, { title: 'No se pudo cargar', message: 'agent_offline', retry: () => { retried += 1; } }));
  assert.ok(screen.getByText('No se pudo cargar'));
  assert.ok(screen.getByText('agent_offline'));
  fireEvent.click(screen.getByText('reintentar'));
  assert.equal(retried, 1);
  view.unmount();
});


test('el menú contextual lista las 6 categorías y los 23 módulos aunque algunos no estén soportados', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = (async () => ({ ok: true, json: async () => ({ items: [] }) })) as unknown as typeof fetch;
  let picked = '';
  const view = render(createElement(ModuleMenu, {
    victim: victim('a', 'ALFA'),
    x: 10,
    y: 10,
    context: { canWrite: true, offline: false, disconnected: false, windows: true, agentVersion: '0.3.7' },
    onPickModule: (entry) => { picked = entry.id; },
    onPickAction: () => undefined,
    onReopen: () => undefined,
    onClose: () => undefined
  }));
  for (const name of ['Sesión y Control', 'Credenciales y Recuperación', 'Sistema y Archivos', 'Captura y Datos', 'Sigilo y Evasión', 'Payload y Despliegue']) {
    assert.ok(screen.getByRole('menuitem', { name: new RegExp(name) }), name);
  }
  const hvnc = screen.getByRole('menuitem', { name: /Hidden VNC/ });
  assert.equal(hvnc.getAttribute('data-disabled'), 'true');
  assert.match(hvnc.getAttribute('title') ?? '', /HVNC/);
  fireEvent.click(screen.getByRole('menuitem', { name: /Remote Shell/ }));
  assert.equal(picked, 'remote-shell');
  view.unmount();
  globalThis.fetch = original;
});

test('el buscador del menú filtra módulos sin perder los no soportados', () => {
  const view = render(createElement(ModuleMenu, {
    victim: victim('a', 'ALFA'),
    x: 10,
    y: 10,
    context: { canWrite: true, offline: false, disconnected: false, windows: true, agentVersion: '0.3.7' },
    onPickModule: () => undefined,
    onPickAction: () => undefined,
    onReopen: () => undefined,
    onClose: () => undefined
  }));
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'geolocation' } });
  assert.ok(screen.getByRole('menuitem', { name: /Geolocation/ }));
  assert.equal(screen.queryByRole('menuitem', { name: /Sesión y Control/ }), null);
  view.unmount();
});


test('el modal de un módulo sin backend muestra código, motivo y reintento', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = (async () => ({ ok: true, json: async () => ({ items: [] }) })) as unknown as typeof fetch;
  const entry = (await import('../src/lib/modules')).MODULES_BY_ID.get('hvnc')!;
  render(createElement(ResultDialog, {
    open: true,
    victim: victim('a', 'ALFA'),
    entry,
    onClose: () => undefined
  }));
  assert.ok(screen.getByRole('dialog', { name: /Hidden VNC/ }));
  assert.ok(screen.getByText(/not_implemented/));
  assert.ok(screen.getAllByText(/no expone un servidor de escritorio oculto/).length > 0);
  assert.ok(screen.getByRole('button', { name: /Reintentar/ }));
  cleanup();
  globalThis.fetch = original;
});

test('el modal pide entrada y ofrece Aceptar/Cancelar antes de ejecutar', async () => {
  const entry = (await import('../src/lib/modules')).MODULES_BY_ID.get('remote-shell')!;
  render(createElement(ResultDialog, {
    open: true,
    victim: victim('a', 'ALFA'),
    entry,
    onClose: () => undefined
  }));
  assert.ok(screen.getByLabelText('Comando'));
  assert.ok(screen.getByRole('button', { name: /Aceptar/ }));
  assert.ok(screen.getByRole('button', { name: /Cancelar/ }));
});


test('el menú conserva las acciones clásicas en su propio bloque', () => {
  let action = '';
  const view = render(createElement(ModuleMenu, {
    victim: victim('a', 'ALFA'),
    x: 10,
    y: 10,
    context: { canWrite: true, offline: false, disconnected: false, windows: true, agentVersion: '0.3.7' },
    onPickModule: () => undefined,
    onPickAction: (picked) => { action = picked.id; },
    onReopen: () => undefined,
    onClose: () => undefined
  }));
  fireEvent.click(screen.getByRole('menuitem', { name: /Acciones del panel/ }));
  fireEvent.click(screen.getByRole('menuitem', { name: /Abrir detalle/ }));
  assert.equal(action, 'open');
  view.unmount();
});


test('los 23 módulos del menú son seleccionables, incluidos los no soportados', async () => {
  const { MODULE_REGISTRY } = await import('../src/lib/modules');
  const picked: string[] = [];
  const view = render(createElement(ModuleMenu, {
    victim: victim('a', 'ALFA'),
    x: 10,
    y: 10,
    context: { canWrite: true, offline: false, disconnected: false, windows: true, agentVersion: '0.3.7' },
    onPickModule: (entry) => { picked.push(entry.id); },
    onPickAction: () => undefined,
    onReopen: () => undefined,
    onClose: () => undefined
  }));
  for (const entry of MODULE_REGISTRY) {
    fireEvent.click(screen.getByRole('menuitem', { name: new RegExp(entry.label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) }));
  }
  assert.deepEqual(picked, MODULE_REGISTRY.map((entry) => entry.id));
  view.unmount();
});

test('cada módulo abre un modal con su título, soportado o no', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = (async () => ({ ok: true, json: async () => ({ items: [] }) })) as unknown as typeof fetch;
  const { MODULE_REGISTRY } = await import('../src/lib/modules');
  for (const entry of MODULE_REGISTRY) {
    const view = render(createElement(ResultDialog, {
      open: true,
      victim: victim('a', 'ALFA'),
      entry,
      onClose: () => undefined
    }));
    assert.ok(screen.getByRole('dialog', { name: new RegExp(entry.label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) }), entry.id);
    view.unmount();
  }
  globalThis.fetch = original;
});


test('la tabla de equipos ya no pinta columnas de checkbox', () => {
  const view = render(createElement(Table, { items: [victim('a', 'ALFA')], loading: false, selected: [], sort: { id: 'seen', dir: 'desc' }, onSelect: () => undefined, onSort: () => undefined, onOpen: () => undefined, onMenu: () => undefined, onBulk: () => undefined }));
  assert.equal(screen.queryByRole('checkbox'), null);
  view.unmount();
});
