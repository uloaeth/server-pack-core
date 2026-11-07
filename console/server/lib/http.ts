/**
 * Parses an `application/json` body. Empty bodies are valid and mean `{}` so
 * mutations such as retry, cancel and logout can be sent without a payload.
 */
export function parseJsonBody(raw: Buffer | string): unknown {
  const text = typeof raw === 'string' ? raw : raw.toString('utf8');
  if (!text.trim()) return {};
  return JSON.parse(text);
}
