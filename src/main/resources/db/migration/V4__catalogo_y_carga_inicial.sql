-- =====================================================================
-- Alejandria MakeUp — Catalogo: unicidad normalizada y CARGA_INICIAL
-- Flyway V4
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. UNICIDAD DE NOMBRES, NORMALIZADA EN EL MOTOR
--
-- Lo que sigue es upper(replace(replace(...))) anidado 54 veces. A simple
-- vista parece ruido generado, asi que conviene dejar por escrito que hace
-- y por que esta aqui: aplana cada letra acentuada de Latin-1 a su
-- equivalente ASCII y luego pasa todo a mayusculas, de modo que dos
-- nombres que solo difieren en tildes o en capitalizacion colisionan.
--
-- POR QUE NO "COLLATE NOCASE", que seria lo obvio: NOCASE solo pliega
-- ASCII. Comprobado sobre SQLite 3.53.2:
--
--     'MAC'        vs 'Mac'          NOCASE lo rechaza
--     'Nina'       vs 'NIÑA'         NOCASE LO ACEPTA
--     'Loreal'     vs 'LORÉAL'       NOCASE LO ACEPTA
--     'Maybelline' vs 'maybelline'   NOCASE LO ACEPTA
--
-- Tres de cuatro casos se colaban. Para una tienda donde las marcas se
-- llaman L'Oreal, Lancome y Estee Lauder, eso no es una red: es un adorno.
--
-- POR QUE NO UNA FUNCION SQL que acorte la expresion: un indice que use
-- una funcion definida por la aplicacion ata el archivo .db a que esa
-- funcion exista al abrirlo. Un respaldo restaurado con cualquier otra
-- herramienta quedaria ilegible, y el respaldo es lo unico que separa a
-- esta tienda de perder su inventario. Fea, pero es del motor.
--
-- POR QUE NO UNA COLUMNA NORMALIZADA con su indice: no hay nada que
-- sincronizar ni estado duplicado que pueda quedar desfasado al renombrar,
-- y no obliga a mapear una columna mas en las entidades.
--
-- EL ORDEN DE LOS REPLACE NO IMPORTA: ningun reemplazo produce un
-- caracter que sea clave de otro. Todas las salidas son ASCII simple.
--
-- LIMITE CONOCIDO: solo cubre Latin-1. La 'o' con macron de 'Shiseido'
-- (U+014D) no se pliega aqui, y el normalizador de Java en
-- NombreNormalizado si la pliega. La diferencia cae del lado seguro: el
-- conjunto que dobla este SQL es un subconjunto estricto del que dobla
-- Java, asi que el indice NUNCA puede rechazar un nombre que el servicio
-- ya aprobo. Lo unico que pierde es fuerza como red de respaldo en ese
-- caso remoto.
-- ---------------------------------------------------------------------

DROP INDEX ux_marca_nombre;
DROP INDEX ux_categoria_nombre;
DROP INDEX ux_variante_combinacion;

CREATE UNIQUE INDEX ux_marca_nombre ON marca
    (upper(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(nombre,'Ÿ','y'),'ÿ','y'),'Ý','y'),'ý','y'),'Ç','c'),'ç','c'),'Ñ','n'),'ñ','n'),'Ü','u'),'ü','u'),'Û','u'),'û','u'),'Ú','u'),'ú','u'),'Ù','u'),'ù','u'),'Ö','o'),'ö','o'),'Õ','o'),'õ','o'),'Ô','o'),'ô','o'),'Ó','o'),'ó','o'),'Ò','o'),'ò','o'),'Ï','i'),'ï','i'),'Î','i'),'î','i'),'Í','i'),'í','i'),'Ì','i'),'ì','i'),'Ë','e'),'ë','e'),'Ê','e'),'ê','e'),'É','e'),'é','e'),'È','e'),'è','e'),'Å','a'),'å','a'),'Ä','a'),'ä','a'),'Ã','a'),'ã','a'),'Â','a'),'â','a'),'Á','a'),'á','a'),'À','a'),'à','a')));

CREATE UNIQUE INDEX ux_categoria_nombre ON categoria
    (upper(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(nombre,'Ÿ','y'),'ÿ','y'),'Ý','y'),'ý','y'),'Ç','c'),'ç','c'),'Ñ','n'),'ñ','n'),'Ü','u'),'ü','u'),'Û','u'),'û','u'),'Ú','u'),'ú','u'),'Ù','u'),'ù','u'),'Ö','o'),'ö','o'),'Õ','o'),'õ','o'),'Ô','o'),'ô','o'),'Ó','o'),'ó','o'),'Ò','o'),'ò','o'),'Ï','i'),'ï','i'),'Î','i'),'î','i'),'Í','i'),'í','i'),'Ì','i'),'ì','i'),'Ë','e'),'ë','e'),'Ê','e'),'ê','e'),'É','e'),'é','e'),'È','e'),'è','e'),'Å','a'),'å','a'),'Ä','a'),'ä','a'),'Ã','a'),'ã','a'),'Â','a'),'â','a'),'Á','a'),'á','a'),'À','a'),'à','a')));

-- producto no tenia indice unico: solo ix_producto_nombre, que no lo es.
-- Su clave natural es (marca, nombre): dos marcas pueden vender cada una
-- su "Labial mate", pero Maybelline no puede tener dos.

CREATE UNIQUE INDEX ux_producto_marca_nombre ON producto
    (marca_id,
     upper(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(nombre,'Ÿ','y'),'ÿ','y'),'Ý','y'),'ý','y'),'Ç','c'),'ç','c'),'Ñ','n'),'ñ','n'),'Ü','u'),'ü','u'),'Û','u'),'û','u'),'Ú','u'),'ú','u'),'Ù','u'),'ù','u'),'Ö','o'),'ö','o'),'Õ','o'),'õ','o'),'Ô','o'),'ô','o'),'Ó','o'),'ó','o'),'Ò','o'),'ò','o'),'Ï','i'),'ï','i'),'Î','i'),'î','i'),'Í','i'),'í','i'),'Ì','i'),'ì','i'),'Ë','e'),'ë','e'),'Ê','e'),'ê','e'),'É','e'),'é','e'),'È','e'),'è','e'),'Å','a'),'å','a'),'Ä','a'),'ä','a'),'Ã','a'),'ã','a'),'Â','a'),'â','a'),'Á','a'),'á','a'),'À','a'),'à','a')));

CREATE UNIQUE INDEX ux_variante_combinacion ON variante
    (producto_id,
     upper(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(coalesce(tono, ''),'Ÿ','y'),'ÿ','y'),'Ý','y'),'ý','y'),'Ç','c'),'ç','c'),'Ñ','n'),'ñ','n'),'Ü','u'),'ü','u'),'Û','u'),'û','u'),'Ú','u'),'ú','u'),'Ù','u'),'ù','u'),'Ö','o'),'ö','o'),'Õ','o'),'õ','o'),'Ô','o'),'ô','o'),'Ó','o'),'ó','o'),'Ò','o'),'ò','o'),'Ï','i'),'ï','i'),'Î','i'),'î','i'),'Í','i'),'í','i'),'Ì','i'),'ì','i'),'Ë','e'),'ë','e'),'Ê','e'),'ê','e'),'É','e'),'é','e'),'È','e'),'è','e'),'Å','a'),'å','a'),'Ä','a'),'ä','a'),'Ã','a'),'ã','a'),'Â','a'),'â','a'),'Á','a'),'á','a'),'À','a'),'à','a')),
     upper(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(coalesce(tamano, ''),'Ÿ','y'),'ÿ','y'),'Ý','y'),'ý','y'),'Ç','c'),'ç','c'),'Ñ','n'),'ñ','n'),'Ü','u'),'ü','u'),'Û','u'),'û','u'),'Ú','u'),'ú','u'),'Ù','u'),'ù','u'),'Ö','o'),'ö','o'),'Õ','o'),'õ','o'),'Ô','o'),'ô','o'),'Ó','o'),'ó','o'),'Ò','o'),'ò','o'),'Ï','i'),'ï','i'),'Î','i'),'î','i'),'Í','i'),'í','i'),'Ì','i'),'ì','i'),'Ë','e'),'ë','e'),'Ê','e'),'ê','e'),'É','e'),'é','e'),'È','e'),'è','e'),'Å','a'),'å','a'),'Ä','a'),'ä','a'),'Ã','a'),'ã','a'),'Â','a'),'â','a'),'Á','a'),'á','a'),'À','a'),'à','a')));


-- ux_variante_codigo_barras se queda como estaba: un codigo de barras es
-- ASCII y plegarle tildes no significa nada.


-- ---------------------------------------------------------------------
-- 2. CARGA_INICIAL EN movimiento_inventario.tipo
--
-- Un CHECK no se puede alterar, asi que toca reconstruir la tabla: crear
-- la nueva, copiar, soltar la vieja, renombrar y recrear los indices. La
-- tabla esta vacia todavia, y este es el ultimo momento barato.
--
-- POR QUE UN TIPO PROPIO Y NO "AJUSTE": un ajuste significa que alguien
-- conto y no cuadro. La carga inicial es el inventario que ya estaba en
-- las vitrinas cuando el sistema empezo a existir. Meterlas en el mismo
-- cajon haria que el primer informe de descuadres mostrara todo el
-- inventario de la tienda como un error de conteo.
--
-- SOBRE LAS FOREIGN KEYS: el procedimiento habitual manda apagarlas con
-- PRAGMA foreign_keys=OFF, pero ese PRAGMA es un no-op dentro de una
-- transaccion y Flyway envuelve cada migracion en una. Aqui no hace
-- falta: ninguna tabla referencia a movimiento_inventario -- es hija, no
-- madre -- asi que soltarla no deja ninguna FK apuntando al vacio.
-- ---------------------------------------------------------------------

CREATE TABLE movimiento_inventario_nuevo (
    id             INTEGER PRIMARY KEY,
    variante_id    INTEGER NOT NULL REFERENCES variante (id) ON DELETE RESTRICT,
    tipo           TEXT    NOT NULL CHECK (tipo IN
                        ('CARGA_INICIAL', 'COMPRA', 'VENTA', 'AJUSTE',
                         'ANULACION', 'MERMA')),
    cantidad       INTEGER NOT NULL CHECK (cantidad <> 0),   -- con signo
    costo_unitario INTEGER NOT NULL DEFAULT 0 CHECK (costo_unitario >= 0),
    compra_id      INTEGER REFERENCES compra (id) ON DELETE RESTRICT,
    venta_id       INTEGER REFERENCES venta (id)  ON DELETE RESTRICT,
    usuario_id     INTEGER NOT NULL REFERENCES usuario (id) ON DELETE RESTRICT,
    fecha          TEXT    NOT NULL,
    motivo         TEXT
);

INSERT INTO movimiento_inventario_nuevo
    (id, variante_id, tipo, cantidad, costo_unitario,
     compra_id, venta_id, usuario_id, fecha, motivo)
SELECT
     id, variante_id, tipo, cantidad, costo_unitario,
     compra_id, venta_id, usuario_id, fecha, motivo
FROM movimiento_inventario;

DROP TABLE movimiento_inventario;

ALTER TABLE movimiento_inventario_nuevo RENAME TO movimiento_inventario;

-- Los indices se fueron con la tabla vieja. Se recrean igual que en V2:
-- sigue sin haber ix_mov_inv_variante, que seria redundante con el
-- compuesto.
CREATE INDEX ix_mov_inv_fecha    ON movimiento_inventario (fecha);
CREATE INDEX ix_mov_inv_tipo     ON movimiento_inventario (tipo);
CREATE INDEX ix_mov_inv_compra   ON movimiento_inventario (compra_id);
CREATE INDEX ix_mov_inv_venta    ON movimiento_inventario (venta_id);
CREATE INDEX ix_mov_inv_variante_fecha ON movimiento_inventario (variante_id, fecha);
