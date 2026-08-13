/**
 * Las fuentes del sistema, empaquetadas con la app.
 *
 * NUNCA DESDE UN CDN. Es una app de escritorio en una tienda: el dia que se caiga
 * el internet, una fuente remota no carga y todo el sistema pasa a Times New
 * Roman. Vite copia estos .woff2 al build, asi que viajan dentro del instalador y
 * no hay ninguna peticion de red.
 *
 * Solo los pesos que declara tokens.css —400, 500 y 600 para la interfaz, 500 para
 * los montos— porque cada peso extra es un archivo mas en el instalador sin que
 * nadie lo pida. Y las dos subfamilias: latin-ext es la que trae los caracteres
 * acentuados, y sin ella las tildes y la ene se resuelven por sustitucion del
 * sistema, que se nota sobre todo en negrita.
 *
 * La guarda de frontend/guardas/verificar.mjs comprueba que ninguna familia ni
 * peso usados en los componentes quede fuera de esta lista: una fuente que no
 * cargo no da error, el navegador sustituye en silencio y la pantalla se ve casi
 * bien.
 */
import '@fontsource/source-sans-3/latin-400.css'
import '@fontsource/source-sans-3/latin-ext-400.css'
import '@fontsource/source-sans-3/latin-500.css'
import '@fontsource/source-sans-3/latin-ext-500.css'
import '@fontsource/source-sans-3/latin-600.css'
import '@fontsource/source-sans-3/latin-ext-600.css'

import '@fontsource/ibm-plex-mono/latin-500.css'
import '@fontsource/ibm-plex-mono/latin-ext-500.css'
