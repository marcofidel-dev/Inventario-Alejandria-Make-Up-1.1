-- =====================================================================
-- Alejandria MakeUp — Notas de sesion de caja
-- Flyway V6
--
-- Migracion puramente aditiva: una tabla y un indice. No reconstruye
-- nada, no altera ningun CHECK, no toca una sola fila existente.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. POR QUE HACE FALTA UNA TABLA Y NO UNA COLUMNA
--
-- El cierre a ciegas manda el conteo fisico y la respuesta a ESA misma
-- llamada es la primera vez que aparecen esperado, contado y diferencia.
-- Eso deja la explicacion de un descuadre sin ningun sitio donde caber:
--
--   sesion_caja.observaciones viaja dentro de CerrarSesionPeticion, o
--   sea ANTES de que se sepa si hubo diferencia. Y para cuando se sabe,
--   la sesion ya esta cerrada y es inmutable.
--
-- Pedir la nota antes captura ruido: se escribe "normal" y despues
-- aparece el faltante. Un campo obligatorio que casi siempre sobra deja
-- de leerse en serio justo el dia que importa.
--
-- Asi que las explicaciones se AGREGAN, igual que movimiento_caja y
-- movimiento_inventario. Nunca UPDATE, nunca DELETE. La fila de
-- sesion_caja sigue siendo inmutable al pie de la letra: sus montos,
-- sus fechas y sus usuarios no se modifican jamas.
--
-- Se puede anotar en cualquier momento, incluido dias despues. Si el
-- martes se descubre que el lunes falto registrar un gasto, se anota el
-- martes con su fecha y su autor. Eso es historia, no correccion, y por
-- eso la nota lleva usuario_id propio: quien explica un descuadre no
-- tiene por que ser quien cerro la caja.
-- ---------------------------------------------------------------------

CREATE TABLE nota_sesion_caja (
    id         INTEGER PRIMARY KEY,
    sesion_id  INTEGER NOT NULL REFERENCES sesion_caja (id) ON DELETE RESTRICT,
    usuario_id INTEGER NOT NULL REFERENCES usuario (id)     ON DELETE RESTRICT,
    fecha      TEXT    NOT NULL,
    texto      TEXT    NOT NULL
);

CREATE INDEX ix_nota_sesion ON nota_sesion_caja (sesion_id);


-- ---------------------------------------------------------------------
-- 2. sesion_caja.observaciones QUEDA EN DESUSO
--
-- No se borra. SQLite no deja quitar una columna sin reconstruir la
-- tabla, y sesion_caja es madre de movimiento_caja, conteo_denominacion,
-- venta y ahora nota_sesion_caja: reconstruirla para ganar nada seria
-- cambiar riesgo por estetica.
--
-- Lo que si queda es escrito: ninguna pantalla vuelve a escribir esa
-- columna. AbrirSesionPeticion y CerrarSesionPeticion mantienen el campo
-- por compatibilidad, pero el front ya no lo manda. Las filas viejas que
-- lo tengan poblado se siguen leyendo; las nuevas nacen en NULL.
-- ---------------------------------------------------------------------
