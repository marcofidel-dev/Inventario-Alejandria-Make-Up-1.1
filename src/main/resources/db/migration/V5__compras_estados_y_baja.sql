-- =====================================================================
-- Alejandria MakeUp — Compras: estados terminales y baja
-- Flyway V5
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. DESCARTADA Y ANULADA EN compra.estado
--
-- La Fase 6 necesita dos actos terminales distintos:
--
--   DESCARTAR  solo sobre un BORRADOR. No hubo mercancia, no se revierte
--              nada. La compra se registro y despues se supo que no iba.
--   ANULAR     solo sobre una RECIBIDA. Hubo movimientos de inventario y
--              hay que devolver el stock.
--
-- Los dos estados no caben en el CHECK de V2, y un CHECK no se puede
-- alterar en SQLite: toca reconstruir la tabla. A diferencia de V4 --que
-- reconstruyo movimiento_inventario copiando las filas-- aqui no se copia
-- nada, porque compra esta vacia: no existe todavia ningun codigo que
-- inserte en ella. Este es el ultimo momento en que sale gratis.
--
-- SOBRE LAS FOREIGN KEYS: compra si es tabla madre, al contrario que
-- movimiento_inventario en V4. La referencian compra_item.compra_id y
-- movimiento_inventario.compra_id. Se verifico que la secuencia
-- DROP / CREATE / recrear indices deja PRAGMA foreign_key_check limpio y
-- las dos FK hijas siguen aplicando: SQLite resuelve el destino de una FK
-- por nombre y en el momento de la sentencia, no al parsear el esquema,
-- asi que la referencia colgante entre el DROP y el CREATE no rompe nada.
-- El PRAGMA foreign_keys=OFF que manda el procedimiento habitual no era
-- opcion: es un no-op dentro de una transaccion, y Flyway envuelve cada
-- migracion en una.
--
-- POR QUE LAS COLUMNAS SE LLAMAN "BAJA" Y NO "ANULACION": cubren los dos
-- actos terminales, y el estado dice cual fue. Un solo juego de columnas
-- hace imposible una fila que afirme que fue descartada y anulada a la
-- vez, que es lo que pasaria con dos juegos paralelos.
-- ---------------------------------------------------------------------

DROP TABLE compra;

CREATE TABLE compra (
    id              INTEGER PRIMARY KEY,
    consecutivo     TEXT    NOT NULL,
    proveedor_id    INTEGER NOT NULL REFERENCES proveedor (id) ON DELETE RESTRICT,
    usuario_id      INTEGER NOT NULL REFERENCES usuario (id)   ON DELETE RESTRICT,
    numero_factura  TEXT,
    total           INTEGER NOT NULL CHECK (total >= 0),
    estado          TEXT    NOT NULL CHECK (estado IN
                        ('BORRADOR', 'RECIBIDA', 'DESCARTADA', 'ANULADA')),
    fecha           TEXT    NOT NULL,
    fecha_recepcion TEXT,
    notas           TEXT,

    -- Quien, cuando y por que se dio de baja. El estado dice si fue
    -- descarte o anulacion.
    fecha_baja      TEXT,
    motivo_baja     TEXT,
    usuario_baja_id INTEGER REFERENCES usuario (id) ON DELETE RESTRICT,

    -- Las tres columnas de baja van juntas o no va ninguna, y solo en los
    -- estados terminales. Sin esto se podria guardar una ANULADA con
    -- motivo pero sin fecha ni autor -- o peor, una RECIBIDA con motivo de
    -- baja, que no significa nada.
    --
    -- Los dos lados son 0 o 1 y nunca NULL (IS NOT NULL nunca devuelve
    -- NULL), asi que la igualdad es total y fuerza las dos direcciones.
    CHECK ((estado IN ('DESCARTADA', 'ANULADA')) =
           (motivo_baja     IS NOT NULL
            AND fecha_baja  IS NOT NULL
            AND usuario_baja_id IS NOT NULL))
);

-- Los indices se fueron con la tabla vieja. Los cuatro de V2 mas dos:
-- usuario_baja_id porque SQLite no indexa las FK solo, y estado porque el
-- listado filtra por el y pone los BORRADOR primero.
CREATE UNIQUE INDEX ux_compra_consecutivo ON compra (consecutivo);
CREATE INDEX ix_compra_proveedor    ON compra (proveedor_id);
CREATE INDEX ix_compra_usuario      ON compra (usuario_id);
CREATE INDEX ix_compra_fecha        ON compra (fecha);
CREATE INDEX ix_compra_usuario_baja ON compra (usuario_baja_id);
CREATE INDEX ix_compra_estado       ON compra (estado);


-- ---------------------------------------------------------------------
-- 2. UNICIDAD NORMALIZADA DEL NOMBRE DE PROVEEDOR
--
-- V4 le puso a marca, categoria, producto y variante un indice unico
-- sobre la expresion normalizada, y dejo escrito por que: COLLATE NOCASE
-- solo pliega ASCII, asi que 'Loreal' contra 'LORÉAL' se le cuela.
--
-- proveedor se quedo con el unique plano de V2 y tiene exactamente el
-- mismo problema, con los mismos nombres: los distribuidores de maquillaje
-- se llaman Distribuciones Lopez, Cosmeticos Nino, Belleza & Cia. El
-- servicio comprueba el duplicado en Java antes de insertar --para poder
-- responder un 409 que nombre al proveedor con el que choca-- y este
-- indice es la red de abajo.
--
-- La expresion es identica, caracter por caracter, a la de V4. Eso no es
-- casual: lo que dobla este SQL tiene que seguir siendo un subconjunto de
-- lo que dobla NombreNormalizado.de(), para que el indice nunca pueda
-- rechazar un nombre que el servicio ya aprobo.
-- ---------------------------------------------------------------------

DROP INDEX ux_proveedor_nombre;

CREATE UNIQUE INDEX ux_proveedor_nombre ON proveedor
    (upper(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(nombre,'Ÿ','y'),'ÿ','y'),'Ý','y'),'ý','y'),'Ç','c'),'ç','c'),'Ñ','n'),'ñ','n'),'Ü','u'),'ü','u'),'Û','u'),'û','u'),'Ú','u'),'ú','u'),'Ù','u'),'ù','u'),'Ö','o'),'ö','o'),'Õ','o'),'õ','o'),'Ô','o'),'ô','o'),'Ó','o'),'ó','o'),'Ò','o'),'ò','o'),'Ï','i'),'ï','i'),'Î','i'),'î','i'),'Í','i'),'í','i'),'Ì','i'),'ì','i'),'Ë','e'),'ë','e'),'Ê','e'),'ê','e'),'É','e'),'é','e'),'È','e'),'è','e'),'Å','a'),'å','a'),'Ä','a'),'ä','a'),'Ã','a'),'ã','a'),'Â','a'),'â','a'),'Á','a'),'á','a'),'À','a'),'à','a')));
