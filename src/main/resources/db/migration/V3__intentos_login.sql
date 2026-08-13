-- =====================================================================
-- Alejandria MakeUp — Intentos de autenticacion
-- Flyway V3
--
-- Bloqueo tras 5 intentos fallidos durante 5 minutos.
--
-- Tabla y no columnas en usuario, por dos razones:
--
--   * Es append-only, como movimiento_inventario y movimiento_caja: el
--     historial de intentos no se edita, se agrega. Y de paso queda una
--     auditoria de quien intento entrar y cuando.
--   * Un intento fallido puede traer un nombre de usuario que no existe,
--     asi que nombre es TEXT y NO una FK a usuario. Registrar tambien
--     esos intentos es el punto: si alguien esta probando nombres, se ve.
--
-- Persistido y no en memoria porque el modelo de amenaza es alguien
-- sentado frente al equipo: si el bloqueo viviera en memoria, cerrar y
-- reabrir la app lo limpiaria en diez segundos.
-- =====================================================================

CREATE TABLE intento_login (
    id     INTEGER PRIMARY KEY,
    nombre TEXT    NOT NULL,
    exito  INTEGER NOT NULL CHECK (exito IN (0, 1)),
    fecha  TEXT    NOT NULL
);

-- La consulta del bloqueo filtra por nombre y ordena por fecha.
CREATE INDEX ix_intento_login_nombre_fecha ON intento_login (nombre, fecha);
