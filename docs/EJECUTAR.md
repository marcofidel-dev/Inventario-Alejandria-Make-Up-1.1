# Cómo correr y empaquetar Alejandria MakeUp

## Desarrollo

Backend (perfil `dev`, consola con DEBUG, sin abrir el navegador solo):

```
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Frontend, si se edita aparte del backend (proxy a `localhost:8080`, ver
`frontend/vite.config.js`):

```
cd frontend && PATH="$PWD/node:$PATH" ./node/npm run dev
```

## Pruebas

Backend, la suite completa:

```
mvn test
```

Frontend —`node`/`npm` no están en el PATH del sistema; el proyecto trae su
propia copia versionada en `frontend/node/`—:

```
cd frontend && PATH="$PWD/node:$PATH" ./node/npm test
```

## Empaquetar

```
./empaquetado/empaquetar.sh
```

Corre `mvn package` (que incluye el build del frontend), la suite de
frontend como compuerta —no empaqueta si queda en rojo o da 0 pruebas—, y
genera:

- `target/app-image/AlejandriaMakeUp.app`
- `target/dmg/AlejandriaMakeUp-<version>.dmg`

## Plataformas

**Windows no es un destino soportado hoy.** El empaquetado y las pruebas
manuales de esta aplicación se hacen y se verifican solo en macOS. La única
plataforma que `empaquetado/empaquetar.sh` produce y que se prueba de
extremo a extremo es macOS (app-image + dmg).
