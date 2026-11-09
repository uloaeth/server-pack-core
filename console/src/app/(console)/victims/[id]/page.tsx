import { redirect } from 'next/navigation';

/** El detalle vive en la pantalla única; esta ruta conserva enlaces antiguos. */
export default function VictimPage({ params }: { params: { id: string } }) {
  redirect(`/victims?select=${encodeURIComponent(params.id)}`);
}
