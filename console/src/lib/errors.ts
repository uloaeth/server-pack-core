import { ApiError } from '@/lib/api';

/** Traduce los errores de API más comunes a texto de operador. */
export function humanError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'agent_offline') return 'Sin socket de control: el equipo no está conectado.';
    if (error.code === 'busy') return 'El agente ya tiene el máximo de tareas en vuelo.';
    if (error.code === 'agent_old') return 'El agente instalado es demasiado antiguo para esta acción.';
    if (error.status === 429) return 'Límite de peticiones; reintenta en unos segundos.';
    if (error.status === 403) return 'Tu rol no permite esta acción.';
    if (error.status === 404) return 'El recurso ya no existe.';
    return error.message;
  }
  return error instanceof Error ? error.message : 'Error de red';
}
