#!/usr/bin/env bash
# Compila con Maven y empaqueta como app de macOS con el icono de la marca.
# Requiere el JDK 21 en JAVA_HOME: jpackage empaqueta el JRE de ESE JDK, no el
# que resuelva el PATH por su cuenta.
set -euo pipefail

aqui="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
raiz="$(dirname "$aqui")"
# Para poder comprobar al final que el .dmg que hay en disco es el que ACABA
# de generar esta corrida, y no uno viejo que jpackage dejó sin tocar.
inicio_epoch=$(date +%s)

if [ -z "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME no está seteado. Este script no adivina cuál java usar:" \
       "exporta JAVA_HOME al JDK 21 antes de correrlo (p. ej. con" \
       "'export JAVA_HOME=\$(/usr/libexec/java_home -v 21)' en una Terminal normal)." >&2
  exit 1
fi
if [ ! -f "$JAVA_HOME/release" ] || ! grep -q '^JAVA_VERSION="21\.' "$JAVA_HOME/release"; then
  echo "JAVA_HOME ($JAVA_HOME) no es un JDK 21." >&2
  exit 1
fi

if ! command -v mvn >/dev/null 2>&1; then
  echo "mvn no está en el PATH. Este script usa el Maven del sistema," \
       "nunca uno portable: instálalo y agrégalo al PATH antes de correr esto." >&2
  exit 1
fi

mvn -f "$raiz/pom.xml" package

# npm run build (parte de `mvn package`, arriba) nunca corre vitest: solo
# corre las guardas y vite build. Por eso una suite de frontend en rojo —o
# completamente rota, 0 pruebas ejecutadas— no frena el build de Maven. Se
# corre aparte, aquí, y si no hay un conteo real de pruebas pasadas se trata
# igual que un fallo: 0 pruebas ejecutadas no es un pendiente, es rojo.
#
# Con el npm del propio frontend/node/, no uno de sistema: a diferencia de
# Maven, el Node/npm del frontend ya está versionado por el pom
# (frontend-maven-plugin) y "mvn package" (arriba) ya lo instaló ahí mismo.
node_frontend="$raiz/frontend/node"
npm_frontend="$node_frontend/npm"
if [ ! -x "$npm_frontend" ]; then
  echo "No se encontró $npm_frontend. ¿Corrió 'mvn package' de verdad arriba?" >&2
  exit 1
fi
# El script de npm resuelve su propio 'node' por PATH; sin anteponer
# frontend/node ahí, falla con "env: node: No such file or directory" aunque
# se lo invoque con ruta absoluta.
#
# El conteo de pruebas NO se lee del texto de la consola: vitest decide esa
# redacción sola —ancho de terminal, color, versión del reporter— y nada de
# eso es un contrato. Un grep contra "Tests  N passed" quedó demostrado
# frágil (cayó con 173 pruebas pasando y exit 0 real). El reporter JSON sí es
# un contrato: --outputFile escribe numTotalTests y numFailedTests como
# números, y esos se leen con el propio node de frontend/node, sin parsear
# texto con espacios.
resultado_test_frontend="$raiz/target/frontend-test-result.json"
rm -f "$resultado_test_frontend"

salida_test_frontend="$(cd "$raiz/frontend" && PATH="$node_frontend:$PATH" "$npm_frontend" test -- \
    --reporter=json --outputFile="$resultado_test_frontend" 2>&1)" \
    && estado_test_frontend=0 || estado_test_frontend=$?
echo "$salida_test_frontend"

if [ $estado_test_frontend -ne 0 ]; then
  echo "npm test del frontend falló (exit $estado_test_frontend). No se empaqueta con la suite en rojo." >&2
  exit 1
fi
if [ ! -f "$resultado_test_frontend" ]; then
  echo "vitest no escribió $resultado_test_frontend; no se puede verificar cuántas pruebas corrieron." >&2
  exit 1
fi

"$node_frontend/node" -e '
const datos = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
if (datos.numTotalTests === 0) {
  console.error("npm test del frontend no ejecutó ninguna prueba (numTotalTests=0). Eso es rojo, "
    + "no un pendiente, aunque el exit code haya sido 0 (p. ej. con --passWithNoTests).");
  process.exit(1);
}
if (datos.numFailedTests > 0) {
  console.error(`npm test del frontend: ${datos.numFailedTests} prueba(s) fallida(s) de ${datos.numTotalTests}.`);
  process.exit(1);
}
console.log(`npm test del frontend: ${datos.numTotalTests} pruebas, 0 fallos.`);
' "$resultado_test_frontend"

# jpackage en macOS exige de 1 a 3 enteros separados por punto, y el primero
# no puede ser cero ni negativo (comprobado corriendo jpackage: rechaza tanto
# "-SNAPSHOT" como "0.0.1"). Se deriva del pom en vez de escribirla a mano
# para que la versión del instalador nunca quede desincronizada de la del jar.
version_pom="$(xmllint --xpath "//*[local-name()='project']/*[local-name()='version']/text()" "$raiz/pom.xml")"
version_app="${version_pom%%-*}"
if ! [[ "$version_app" =~ ^[1-9][0-9]*(\.[0-9]+){0,2}$ ]]; then
  echo "La versión del pom ($version_pom -> $version_app, tras quitar el sufijo) no es" \
       "válida para jpackage en macOS: necesita de 1 a 3 enteros separados por punto y" \
       "el primero no puede ser 0. Sube la versión en pom.xml (p. ej. a 1.0.0) antes de" \
       "empaquetar." >&2
  exit 1
fi

# El PNG de origen puede llegar como JPEG renombrado a .png (pasa con exportes
# de algunos editores). sips hereda el formato de ORIGEN si no se fuerza con
# -s format png: el iconset queda compuesto de JPEGs con extensión .png y
# iconutil falla con "Failed to generate ICNS" sin más detalle.
origen_icono="$aqui/Alejandriaicon.png"
iconset="$aqui/alejandria.iconset"
icns="$aqui/alejandria.icns"

rm -rf "$iconset" "$icns"
mkdir -p "$iconset"

sips -s format png -z 16 16     "$origen_icono" --out "$iconset/icon_16x16.png"      >/dev/null
sips -s format png -z 32 32     "$origen_icono" --out "$iconset/icon_16x16@2x.png"   >/dev/null
sips -s format png -z 32 32     "$origen_icono" --out "$iconset/icon_32x32.png"      >/dev/null
sips -s format png -z 64 64     "$origen_icono" --out "$iconset/icon_32x32@2x.png"   >/dev/null
sips -s format png -z 128 128   "$origen_icono" --out "$iconset/icon_128x128.png"    >/dev/null
sips -s format png -z 256 256   "$origen_icono" --out "$iconset/icon_128x128@2x.png" >/dev/null
sips -s format png -z 256 256   "$origen_icono" --out "$iconset/icon_256x256.png"    >/dev/null
sips -s format png -z 512 512   "$origen_icono" --out "$iconset/icon_256x256@2x.png" >/dev/null
sips -s format png -z 512 512   "$origen_icono" --out "$iconset/icon_512x512.png"    >/dev/null
# icon_512x512@2x (1024px) queda fuera a propósito: el origen es de 640px y
# generarlo exigiría ampliar la imagen, no reducirla.

if ! iconutil -c icns "$iconset" -o "$icns"; then
  echo "iconutil rechazó el iconset sin icon_512x512@2x. No se rellena con una imagen ampliada: revisar a mano." >&2
  exit 1
fi

# --input solo con el jar: si fuera target/, jpackage copiaría target/app-image dentro de sí mismo en cada corrida.
entrada="$raiz/target/entrada"
app_image_dest="$raiz/target/app-image"
dmg_dest="$raiz/target/dmg"
rm -rf "$entrada" "$app_image_dest" "$dmg_dest"
mkdir -p "$entrada"
cp "$raiz/target/pos.jar" "$entrada"

# Primero el app-image: es lo que se codesign-ea y se prueba corriéndolo
# directo. El .dmg es el producto final, empaquetado desde ESE mismo
# app-image (--app-image), nunca reconstruido desde cero, para que el
# instalador sea exactamente lo que se firmó y probó.
"$JAVA_HOME/bin/jpackage" --type app-image --name AlejandriaMakeUp \
    --input "$entrada" --main-jar pos.jar \
    --icon "$icns" \
    --app-version "$version_app" \
    --dest "$app_image_dest"

"$JAVA_HOME/bin/jpackage" --type dmg --name AlejandriaMakeUp \
    --app-image "$app_image_dest/AlejandriaMakeUp.app" \
    --dest "$dmg_dest"

# No se asume el nombre: jpackage en macOS normaliza la versión del .dmg —
# "1.0.0" sale como "AlejandriaMakeUp-1.0.dmg", sin el ".0" final— y adivinar
# esa normalización a mano es más frágil que preguntarle al filesystem.
# $dmg_dest se vació con rm -rf arriba, antes de jpackage, así que cualquier
# .dmg que haya ahora lo puso jpackage en esta corrida; más de uno es un bug
# en otra parte del script, no algo para resolver eligiendo cualquiera.
dmgs=("$dmg_dest"/*.dmg)
if [ ! -f "${dmgs[0]:-}" ]; then
  echo "jpackage no generó ningún .dmg en $dmg_dest." >&2
  exit 1
fi
if [ "${#dmgs[@]}" -ne 1 ]; then
  echo "Hay ${#dmgs[@]} archivos .dmg en $dmg_dest, se esperaba exactamente 1: ${dmgs[*]}" >&2
  exit 1
fi
dmg_archivo="${dmgs[0]}"

# rm -rf "$dmg_dest" corrió arriba, antes de jpackage: si el archivo que hay
# ahora es anterior al inicio de ESTA corrida, jpackage no lo escribió de
# verdad (pudo quedar de un hardlink, una corrida anterior en otro proceso, o
# un jpackage que no sobrescribió). Un .dmg viejo pasando por nuevo es peor
# que el script fallando.
dmg_mtime=$(stat -f %m "$dmg_archivo")
if [ "$dmg_mtime" -lt "$inicio_epoch" ]; then
  echo "$dmg_archivo es anterior al inicio de esta corrida" \
       "($(date -r "$dmg_mtime") < $(date -r "$inicio_epoch")); jpackage no lo generó de verdad." >&2
  exit 1
fi

dmg_tamano=$(stat -f %z "$dmg_archivo")
echo "Empaquetado listo: $dmg_archivo"
echo "  Generado: $(date -r "$dmg_mtime")"
echo "  Tamaño:   $dmg_tamano bytes"
