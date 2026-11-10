/**
 * Logotipo de araña en pixel art (16×16). Se dibuja con rectángulos y
 * `shape-rendering: crispEdges` para que escale sin suavizado, como un icono
 * de sistema de la época. Hereda el color con `currentColor`.
 */
export function SpiderLogo({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 16 16"
      width="16"
      height="16"
      className={className}
      shapeRendering="crispEdges"
      aria-hidden="true"
      focusable="false"
    >
      <g fill="currentColor">
        {/* patas izquierdas */}
        <rect x="3" y="5" width="2" height="1" />
        <rect x="1" y="6" width="3" height="1" />
        <rect x="2" y="7" width="3" height="1" />
        <rect x="3" y="9" width="2" height="1" />
        <rect x="1" y="10" width="3" height="1" />
        <rect x="2" y="11" width="3" height="1" />
        {/* patas derechas */}
        <rect x="11" y="5" width="2" height="1" />
        <rect x="12" y="6" width="3" height="1" />
        <rect x="11" y="7" width="3" height="1" />
        <rect x="11" y="9" width="2" height="1" />
        <rect x="12" y="10" width="3" height="1" />
        <rect x="11" y="11" width="3" height="1" />
        {/* cuerpo */}
        <rect x="6" y="2" width="4" height="2" />
        <rect x="5" y="4" width="6" height="4" />
        <rect x="4" y="8" width="8" height="4" />
        <rect x="5" y="12" width="6" height="2" />
      </g>
      {/* ojos */}
      <rect x="6" y="3" width="1" height="1" fill="#fff" />
      <rect x="9" y="3" width="1" height="1" fill="#fff" />
      {/* brillo y sombra del abdomen */}
      <rect x="6" y="8" width="4" height="1" fill="#fff" opacity="0.75" />
      <rect x="5" y="11" width="6" height="1" fill="#fff" opacity="0.35" />
    </svg>
  );
}
