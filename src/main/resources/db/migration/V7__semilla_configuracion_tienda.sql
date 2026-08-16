-- =====================================================================
-- Alejandria MakeUp — Configuracion de la tienda
-- Flyway V7
--
-- La tabla clave/valor existe desde V1 y hasta hoy no la habia usado
-- nadie. Esta migracion la pone en minuscula y siembra las cinco claves
-- que el encabezado del recibo necesita.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 0. POR QUE HAY QUE RENOMBRARLA
--
-- V1 escribio `CREATE TABLE Configuracion`, con mayuscula, y todas las
-- tablas de V2 en adelante van en minuscula. Para SQLite da lo mismo:
-- compara identificadores sin distinguir caja.
--
-- Para Hibernate NO. La estrategia de nombres de Spring Boot pasa a
-- minuscula hasta el nombre que se escriba a mano en @Table, asi que la
-- entidad busca `configuracion` mientras los metadatos JDBC devuelven
-- `Configuracion`, y `ddl-auto: validate` tumba el arranque entero con
-- "Schema validation: missing table [configuracion]". No es un error que
-- se pueda esquivar desde el codigo: hay que renombrar la tabla.
--
-- En SQLite un RENAME que solo cambia la caja no sirve —los dos nombres
-- son el mismo nombre—, asi que se pasa por un nombre intermedio y se
-- copian las filas. Copiar y no DROP: la tabla deberia estar vacia, pero
-- "deberia estar vacia" no es razon para borrar datos de nadie.
-- ---------------------------------------------------------------------

ALTER TABLE Configuracion RENAME TO configuracion_v1;

CREATE TABLE configuracion (
    clave TEXT PRIMARY KEY,
    valor TEXT
);

INSERT INTO configuracion (clave, valor)
SELECT clave, valor FROM configuracion_v1;

DROP TABLE configuracion_v1;


-- ---------------------------------------------------------------------
-- 1. EN BLANCO, NO INVENTADAS
--
-- Ni un nombre de ejemplo, ni un NIT de relleno, ni una direccion de
-- muestra. Un valor inventado se imprime igual que uno real: el recibo
-- saldria con datos falsos y nadie lo notaria hasta que una clienta
-- pregunte por una direccion que no existe. Vacio se ve vacio, y la
-- pantalla de configuracion lo advierte.
--
-- INSERT OR IGNORE y no INSERT a secas: si una base ya tuviera alguna de
-- estas claves escrita a mano, esta migracion no puede pisarla.
-- ---------------------------------------------------------------------

INSERT OR IGNORE INTO configuracion (clave, valor) VALUES
    ('tienda.nombre',     ''),
    ('tienda.nit',        ''),
    ('tienda.direccion',  ''),
    ('tienda.telefono',   ''),
    ('tienda.pie_recibo', '');


-- ---------------------------------------------------------------------
-- 2. POR QUE FALTAR NO PUEDE ROMPER EL RECIBO
--
-- El recibo se genera IGUAL aunque estas cinco esten vacias, con el
-- encabezado incompleto. Un encabezado a medias es mejor que una venta
-- sin comprobante: la clienta ya pago y se va con el producto.
--
-- Por eso el servicio lee con getOrDefault("") en vez de exigir que la
-- fila exista. Esta semilla es para que la pantalla de configuracion
-- abra con los campos en su sitio, no una precondicion del cobro.
-- ---------------------------------------------------------------------
