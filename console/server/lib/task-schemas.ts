import schemas from '../../../contract/v1/task-schemas.json';

type TaskName = keyof typeof schemas.types;

const KNOWN = Object.keys(schemas.types) as TaskName[];

/** Mirrors the agent validator; `null` means the params fit the contract. */
export function validateParams(name: string, params: Record<string, unknown>): string | null {
  if (!KNOWN.includes(name as TaskName)) return `unknown_task: ${name}`;
  const schema = schemas.types[name as TaskName] as {
    transport?: string;
    params: {
      required?: string[];
      additionalProperties?: boolean;
      properties?: Record<string, { type?: string; minimum?: number; maximum?: number; maxLength?: number }>;
    };
  };
  const spec = schema.params;
  if (schema.transport !== 'http' && schema.transport !== 'frame') return `invalid_transport: ${name}`;
  const properties = spec.properties ?? {};
  for (const key of Object.keys(params)) {
    if (!(key in properties)) return `unknown_param: ${key}`;
  }
  for (const key of spec.required ?? []) {
    const value = params[key];
    if (value === undefined || value === null || String(value).trim() === '') return `missing_param: ${key}`;
  }
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    const rule = properties[key];
    if (!rule) continue;
    if (rule.type === 'integer') {
      if (!Number.isInteger(value)) return `invalid_param: ${key}`;
      if (rule.minimum !== undefined && (value as number) < rule.minimum) {
        return `invalid_param: ${key} (${rule.minimum}..${rule.maximum})`;
      }
      if (rule.maximum !== undefined && (value as number) > rule.maximum) {
        return `invalid_param: ${key} (${rule.minimum}..${rule.maximum})`;
      }
    }
    if (rule.type === 'string' && typeof value === 'string' && rule.maxLength && value.length > rule.maxLength) {
      return `invalid_param: ${key}`;
    }
    if (rule.type === 'boolean' && typeof value !== 'boolean') return `invalid_param: ${key}`;
  }
  return null;
}

export function isKnownTask(name: string): boolean {
  return KNOWN.includes(name as TaskName);
}

/**
 * Result transport from the schema. An absent field means `frame` on purpose:
 * only the five http_only names carry `http`, and nothing is ever undefined.
 * The route validates the field presence at the edge before dispatch.
 */
export function transportOf(name: string): 'http' | 'frame' {
  const schema = schemas.types[name as TaskName] as { transport?: string } | undefined;
  return schema?.transport === 'http' ? 'http' : 'frame';
}
