#!/usr/bin/env bash
# Compila con Maven y empaqueta como app de macOS con el icono de la marca.
# Requiere el JDK 21 en JAVA_HOME: jpackage empaqueta el JRE de ESE JDK, no el
# que resuelva el PATH por su cuenta.
set -euo pipefail

aqui="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
raiz="$(dirname "$aqui")"

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
