const MIME: Record<string, string> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  webp: 'image/webp',
  gif: 'image/gif',
  bmp: 'image/bmp',
  wav: 'audio/wav',
  mp3: 'audio/mpeg',
  ogg: 'audio/ogg',
  webm: 'video/webm',
  mp4: 'video/mp4',
  txt: 'text/plain; charset=utf-8',
  log: 'text/plain; charset=utf-8',
  json: 'application/json; charset=utf-8',
  jsonl: 'application/x-ndjson; charset=utf-8',
  csv: 'text/csv; charset=utf-8',
  zip: 'application/zip'
};

/** MIME por extensión para previsualizar artefactos sin confiar en el manifest. */
export function mimeFor(path: string): string {
  const ext = path.split('.').pop()?.toLowerCase() ?? '';
  return MIME[ext] ?? 'application/octet-stream';
}

/** `true` si el navegador puede pintar el artefacto en un `<img>`, `<audio>` o `<video>`. */
export function isPreviewable(mime: string): boolean {
  return /^(image|audio|video)\//.test(mime) || mime.startsWith('text/') || mime.startsWith('application/json');
}
