# Empaqueta target/pos.jar (mvn package) como app de Windows con el icono de la marca.
# Requiere el JDK 21: jpackage empaqueta el JRE del JDK activo.
$ErrorActionPreference = 'Stop'
$raiz = Split-Path $PSScriptRoot
if ($env:JAVA_HOME -notmatch 'jdk-21') { throw "JAVA_HOME no apunta al JDK 21: $env:JAVA_HOME" }

# --input solo con el jar: si fuera target/, jpackage copiaría target/instalador dentro de sí mismo en cada corrida.
$entrada = "$raiz\target\entrada"
Remove-Item $entrada, "$raiz\target\app-image" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $entrada | Out-Null
Copy-Item "$raiz\target\pos.jar" $entrada

jpackage --type app-image --name AlejandriaMakeUp `
    --input $entrada --main-jar pos.jar `
    --icon "$PSScriptRoot\alejandria.ico" `
    --dest "$raiz\target\app-image"
