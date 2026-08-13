-- =====================================================================
-- Alejandria MakeUp — Esquema de dominio completo
-- Flyway V2
--
-- CONVENCIONES
--   * Todo monto es INTEGER: pesos colombianos enteros. Nunca REAL.
--   * Todas las fechas son TEXT en ISO-8601 ('YYYY-MM-DD HH:MM:SS').
--   * Los booleanos son INTEGER 0/1.
--   * SQLite no tiene ENUM: se usan CHECK.
--   * SQLite NO indexa las foreign keys automaticamente. Van explicitas.
--   * ON DELETE RESTRICT en todo: aqui no se borra nada, se anula.
-- =====================================================================


-- ---------------------------------------------------------------------
-- USUARIOS
-- ---------------------------------------------------------------------
CREATE TABLE usuario (
    id              INTEGER PRIMARY KEY,
    nombre          TEXT    NOT NULL,
    pin_hash        TEXT    NOT NULL,              -- BCrypt
    rol             TEXT    NOT NULL CHECK (rol IN ('DUENA', 'EMPLEADA')),
    activo          INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1)),
    fecha_creacion  TEXT    NOT NULL
);

CREATE UNIQUE INDEX ux_usuario_nombre ON usuario (nombre);


-- ---------------------------------------------------------------------
-- CLIENTES
-- ---------------------------------------------------------------------
CREATE TABLE cliente (
    id              INTEGER PRIMARY KEY,
    nombre          TEXT    NOT NULL,
    whatsapp        TEXT,
    notas           TEXT,
    fecha_creacion  TEXT    NOT NULL
);

CREATE INDEX ix_cliente_nombre   ON cliente (nombre);
CREATE INDEX ix_cliente_whatsapp ON cliente (whatsapp);


-- ---------------------------------------------------------------------
-- CATALOGO
-- ---------------------------------------------------------------------
CREATE TABLE marca (
    id      INTEGER PRIMARY KEY,
    nombre  TEXT    NOT NULL,
    activo  INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1))
);

CREATE UNIQUE INDEX ux_marca_nombre ON marca (nombre);


CREATE TABLE categoria (
    id      INTEGER PRIMARY KEY,
    nombre  TEXT    NOT NULL,
    activo  INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1))
);

CREATE UNIQUE INDEX ux_categoria_nombre ON categoria (nombre);


-- El producto NO tiene stock ni precio. Esos viven en la variante.
CREATE TABLE producto (
    id            INTEGER PRIMARY KEY,
    nombre        TEXT    NOT NULL,
    marca_id      INTEGER NOT NULL REFERENCES marca (id)     ON DELETE RESTRICT,
    categoria_id  INTEGER NOT NULL REFERENCES categoria (id) ON DELETE RESTRICT,
    descripcion   TEXT,
    activo        INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1)),
    fecha_creacion TEXT   NOT NULL
);

CREATE INDEX ix_producto_marca     ON producto (marca_id);
CREATE INDEX ix_producto_categoria ON producto (categoria_id);
CREATE INDEX ix_producto_nombre    ON producto (nombre);


-- La variante es la unidad real de todo el sistema.
CREATE TABLE variante (
    id                INTEGER PRIMARY KEY,
    producto_id       INTEGER NOT NULL REFERENCES producto (id) ON DELETE RESTRICT,
    tono              TEXT,
    tamano            TEXT,
    codigo_barras     TEXT,
    precio_venta      INTEGER NOT NULL CHECK (precio_venta   >= 0),
    costo_promedio    INTEGER NOT NULL DEFAULT 0 CHECK (costo_promedio >= 0),
    stock_minimo      INTEGER NOT NULL DEFAULT 0 CHECK (stock_minimo   >= 0),
    fecha_vencimiento TEXT,
    pao_meses         INTEGER,
    activo            INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1)),
    fecha_creacion    TEXT    NOT NULL
);

CREATE INDEX ix_variante_producto            ON variante (producto_id);
CREATE UNIQUE INDEX ux_variante_codigo_barras ON variante (codigo_barras)
    WHERE codigo_barras IS NOT NULL;

-- Indice sobre expresion, y no sobre las columnas desnudas: en SQLite (como en
-- el estandar) los NULL son distintos entre si en un indice unico, asi que
-- (producto_id, tono, tamano) permitiria dos variantes del mismo producto con
-- tono y tamano nulos -- justo el caso del producto sin variantes
-- diferenciadas, el mas comun del catalogo. El COALESCE los aplana a cadena
-- vacia para que si colisionen.
CREATE UNIQUE INDEX ux_variante_combinacion  ON variante
    (producto_id, COALESCE(tono, ''), COALESCE(tamano, ''));


-- ---------------------------------------------------------------------
-- PROVEEDORES Y COMPRAS
-- ---------------------------------------------------------------------
CREATE TABLE proveedor (
    id             INTEGER PRIMARY KEY,
    nombre         TEXT    NOT NULL,
    nit            TEXT,
    telefono       TEXT,
    contacto       TEXT,
    notas          TEXT,
    activo         INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1)),
    fecha_creacion TEXT    NOT NULL
);

CREATE UNIQUE INDEX ux_proveedor_nombre ON proveedor (nombre);


CREATE TABLE compra (
    id              INTEGER PRIMARY KEY,
    consecutivo     TEXT    NOT NULL,
    proveedor_id    INTEGER NOT NULL REFERENCES proveedor (id) ON DELETE RESTRICT,
    usuario_id      INTEGER NOT NULL REFERENCES usuario (id)   ON DELETE RESTRICT,
    numero_factura  TEXT,
    total           INTEGER NOT NULL CHECK (total >= 0),
    estado          TEXT    NOT NULL CHECK (estado IN ('BORRADOR', 'RECIBIDA')),
    fecha           TEXT    NOT NULL,
    fecha_recepcion TEXT,
    notas           TEXT
);

CREATE UNIQUE INDEX ux_compra_consecutivo ON compra (consecutivo);
CREATE INDEX ix_compra_proveedor ON compra (proveedor_id);
CREATE INDEX ix_compra_usuario   ON compra (usuario_id);
CREATE INDEX ix_compra_fecha     ON compra (fecha);


CREATE TABLE compra_item (
    id             INTEGER PRIMARY KEY,
    compra_id      INTEGER NOT NULL REFERENCES compra (id)   ON DELETE RESTRICT,
    variante_id    INTEGER NOT NULL REFERENCES variante (id) ON DELETE RESTRICT,
    cantidad       INTEGER NOT NULL CHECK (cantidad > 0),
    costo_unitario INTEGER NOT NULL CHECK (costo_unitario >= 0),
    subtotal       INTEGER NOT NULL CHECK (subtotal >= 0)
);

CREATE INDEX ix_compra_item_compra   ON compra_item (compra_id);
CREATE INDEX ix_compra_item_variante ON compra_item (variante_id);


-- ---------------------------------------------------------------------
-- CAJA
-- ---------------------------------------------------------------------
CREATE TABLE sesion_caja (
    id                  INTEGER PRIMARY KEY,
    consecutivo         TEXT    NOT NULL,
    usuario_apertura_id INTEGER NOT NULL REFERENCES usuario (id) ON DELETE RESTRICT,
    usuario_cierre_id   INTEGER          REFERENCES usuario (id) ON DELETE RESTRICT,
    fecha_apertura      TEXT    NOT NULL,
    fecha_cierre        TEXT,
    base_inicial        INTEGER NOT NULL CHECK (base_inicial >= 0),
    -- Los tres siguientes se congelan al cerrar y no se recalculan nunca mas.
    efectivo_esperado   INTEGER,
    efectivo_contado    INTEGER,
    diferencia          INTEGER,
    monto_retirado      INTEGER,
    base_siguiente      INTEGER,
    estado              TEXT    NOT NULL CHECK (estado IN ('ABIERTA', 'CERRADA')),
    observaciones       TEXT
);

CREATE UNIQUE INDEX ux_sesion_caja_consecutivo ON sesion_caja (consecutivo);
CREATE INDEX ix_sesion_caja_estado ON sesion_caja (estado);
CREATE INDEX ix_sesion_caja_fecha  ON sesion_caja (fecha_apertura);

-- Regla dura: una sola sesion abierta a la vez en todo el sistema.
CREATE UNIQUE INDEX ux_sesion_caja_unica_abierta
    ON sesion_caja (estado) WHERE estado = 'ABIERTA';


-- Conteo por denominacion al cerrar. Tabla aparte y no 10 columnas.
CREATE TABLE conteo_denominacion (
    id           INTEGER PRIMARY KEY,
    sesion_id    INTEGER NOT NULL REFERENCES sesion_caja (id) ON DELETE RESTRICT,
    denominacion INTEGER NOT NULL CHECK (denominacion > 0),
    cantidad     INTEGER NOT NULL CHECK (cantidad >= 0)
);

CREATE UNIQUE INDEX ux_conteo_sesion_denom ON conteo_denominacion (sesion_id, denominacion);


-- ---------------------------------------------------------------------
-- VENTAS
-- ---------------------------------------------------------------------
CREATE TABLE venta (
    id             INTEGER PRIMARY KEY,
    uuid           TEXT    NOT NULL,
    consecutivo    TEXT    NOT NULL,
    sesion_caja_id INTEGER NOT NULL REFERENCES sesion_caja (id) ON DELETE RESTRICT,
    usuario_id     INTEGER NOT NULL REFERENCES usuario (id)     ON DELETE RESTRICT,
    cliente_id     INTEGER          REFERENCES cliente (id)     ON DELETE RESTRICT,
    fecha          TEXT    NOT NULL,
    subtotal       INTEGER NOT NULL CHECK (subtotal  >= 0),
    descuento      INTEGER NOT NULL DEFAULT 0 CHECK (descuento >= 0),
    total          INTEGER NOT NULL CHECK (total     >= 0),
    metodo_pago    TEXT    NOT NULL CHECK (metodo_pago IN
                        ('EFECTIVO', 'TARJETA', 'NEQUI', 'DAVIPLATA', 'TRANSFERENCIA')),
    efectivo_recibido INTEGER,
    cambio            INTEGER,
    estado         TEXT    NOT NULL CHECK (estado IN ('COMPLETADA', 'ANULADA')),
    fecha_anulacion TEXT,
    motivo_anulacion TEXT,
    usuario_anulacion_id INTEGER REFERENCES usuario (id) ON DELETE RESTRICT,
    ruta_recibo    TEXT
);

CREATE UNIQUE INDEX ux_venta_uuid        ON venta (uuid);
CREATE UNIQUE INDEX ux_venta_consecutivo ON venta (consecutivo);
CREATE INDEX ix_venta_sesion   ON venta (sesion_caja_id);
CREATE INDEX ix_venta_usuario  ON venta (usuario_id);
CREATE INDEX ix_venta_cliente  ON venta (cliente_id);
CREATE INDEX ix_venta_fecha    ON venta (fecha);
CREATE INDEX ix_venta_estado   ON venta (estado);


-- Precio, costo y descripcion CONGELADOS al momento de la venta.
-- descuento_prorrateado: el descuento de cabecera repartido proporcionalmente
-- y congelado aqui, para que el margen por producto no dependa de recalcular.
CREATE TABLE venta_item (
    id                        INTEGER PRIMARY KEY,
    venta_id                  INTEGER NOT NULL REFERENCES venta (id)    ON DELETE RESTRICT,
    variante_id               INTEGER NOT NULL REFERENCES variante (id) ON DELETE RESTRICT,
    cantidad                  INTEGER NOT NULL CHECK (cantidad > 0),
    precio_unitario_congelado INTEGER NOT NULL CHECK (precio_unitario_congelado >= 0),
    costo_unitario_congelado  INTEGER NOT NULL CHECK (costo_unitario_congelado  >= 0),
    descripcion_congelada     TEXT    NOT NULL,
    descuento_prorrateado     INTEGER NOT NULL DEFAULT 0 CHECK (descuento_prorrateado >= 0),
    subtotal                  INTEGER NOT NULL CHECK (subtotal >= 0)
);

CREATE INDEX ix_venta_item_venta    ON venta_item (venta_id);
CREATE INDEX ix_venta_item_variante ON venta_item (variante_id);


-- ---------------------------------------------------------------------
-- LEDGERS APPEND-ONLY
-- Nunca UPDATE. Nunca DELETE. Un error se corrige con otro movimiento.
-- ---------------------------------------------------------------------

-- El stock ES la suma de cantidad sobre esta tabla.
CREATE TABLE movimiento_inventario (
    id             INTEGER PRIMARY KEY,
    variante_id    INTEGER NOT NULL REFERENCES variante (id) ON DELETE RESTRICT,
    tipo           TEXT    NOT NULL CHECK (tipo IN
                        ('COMPRA', 'VENTA', 'AJUSTE', 'ANULACION', 'MERMA')),
    cantidad       INTEGER NOT NULL CHECK (cantidad <> 0),   -- con signo
    costo_unitario INTEGER NOT NULL DEFAULT 0 CHECK (costo_unitario >= 0),
    compra_id      INTEGER REFERENCES compra (id) ON DELETE RESTRICT,
    venta_id       INTEGER REFERENCES venta (id)  ON DELETE RESTRICT,
    usuario_id     INTEGER NOT NULL REFERENCES usuario (id) ON DELETE RESTRICT,
    fecha          TEXT    NOT NULL,
    motivo         TEXT
);

-- No hay ix_mov_inv_variante: seria redundante con ix_mov_inv_variante_fecha,
-- que ya cubre variante_id como prefijo izquierdo.
CREATE INDEX ix_mov_inv_fecha    ON movimiento_inventario (fecha);
CREATE INDEX ix_mov_inv_tipo     ON movimiento_inventario (tipo);
CREATE INDEX ix_mov_inv_compra   ON movimiento_inventario (compra_id);
CREATE INDEX ix_mov_inv_venta    ON movimiento_inventario (venta_id);
-- Indice compuesto: es la consulta mas caliente del sistema (stock por variante).
CREATE INDEX ix_mov_inv_variante_fecha ON movimiento_inventario (variante_id, fecha);


-- Solo el efectivo toca el cajon. Las ventas por otros medios NO generan
-- movimiento aqui: se concilian aparte contra el extracto.
CREATE TABLE movimiento_caja (
    id         INTEGER PRIMARY KEY,
    sesion_id  INTEGER NOT NULL REFERENCES sesion_caja (id) ON DELETE RESTRICT,
    tipo       TEXT    NOT NULL CHECK (tipo IN
                    ('VENTA_EFECTIVO', 'RETIRO', 'INGRESO', 'GASTO', 'ANULACION')),
    monto      INTEGER NOT NULL CHECK (monto <> 0),          -- con signo
    venta_id   INTEGER REFERENCES venta (id)   ON DELETE RESTRICT,
    usuario_id INTEGER NOT NULL REFERENCES usuario (id) ON DELETE RESTRICT,
    fecha      TEXT    NOT NULL,
    concepto   TEXT
);

CREATE INDEX ix_mov_caja_sesion ON movimiento_caja (sesion_id);
CREATE INDEX ix_mov_caja_fecha  ON movimiento_caja (fecha);
CREATE INDEX ix_mov_caja_tipo   ON movimiento_caja (tipo);
CREATE INDEX ix_mov_caja_venta  ON movimiento_caja (venta_id);


-- ---------------------------------------------------------------------
-- CONSECUTIVOS
-- El AUTOINCREMENT de SQLite salta numeros si una transaccion hace rollback.
-- Estos se incrementan dentro de la misma transaccion del documento.
-- ---------------------------------------------------------------------
CREATE TABLE consecutivo (
    tipo          TEXT    PRIMARY KEY CHECK (tipo IN ('VENTA', 'COMPRA', 'SESION_CAJA')),
    prefijo       TEXT    NOT NULL,
    ultimo_numero INTEGER NOT NULL DEFAULT 0 CHECK (ultimo_numero >= 0)
);

INSERT INTO consecutivo (tipo, prefijo, ultimo_numero) VALUES
    ('VENTA',       'V', 0),
    ('COMPRA',      'C', 0),
    ('SESION_CAJA', 'S', 0);
