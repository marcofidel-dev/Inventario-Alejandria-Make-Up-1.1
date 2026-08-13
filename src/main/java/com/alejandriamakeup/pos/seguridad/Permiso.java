package com.alejandriamakeup.pos.seguridad;

/**
 * Capacidades del dominio, no endpoints. Los endpoints cambian de forma entre
 * fases; lo que una EMPLEADA puede o no puede hacer, no.
 *
 * <p>Al agregar un permiso hay que clasificarlo en {@link PermisosPorRol}: un test
 * recorre este enum completo y falla si alguno quedó sin decisión.
 */
public enum Permiso {

    /** Abrir y cerrar sesiones de caja. */
    OPERAR_CAJA,

    /** Registrar retiros, ingresos y gastos manuales. */
    REGISTRAR_MOVIMIENTO_CAJA,

    /** Ver sesiones de caja abiertas o cerradas por otra persona. */
    VER_SESIONES_DE_OTROS,

    /** Disparar un respaldo manual. */
    RESPALDAR,

    /** Ver costos, costo promedio y márgenes. */
    VER_COSTOS_Y_MARGENES,

    /** Entrar al módulo de métricas. */
    VER_METRICAS,

    /** Crear o modificar marcas, categorías, productos, variantes y precios. */
    EDITAR_CATALOGO,

    /** Registrar ajustes de inventario. */
    AJUSTAR_INVENTARIO,

    /**
     * Registrar la carga inicial de existencias. Separado de
     * {@link #AJUSTAR_INVENTARIO} porque son actos distintos: cargar el inventario
     * es una tarea de puesta en marcha, ajustar es corregir la operación del día.
     */
    CARGAR_INVENTARIO_INICIAL,

    /** Anular una venta ya registrada. */
    ANULAR_VENTAS,

    /** Crear, editar y desactivar proveedores. */
    GESTIONAR_PROVEEDORES,

    /**
     * Registrar una compra en borrador, editarla y descartarla. Nada de esto toca
     * el inventario: un borrador es una factura copiada, no mercancía que entró.
     */
    REGISTRAR_COMPRAS,

    /**
     * Recibir una compra: generar sus movimientos de entrada y recalcular el costo
     * promedio de las variantes. Separado de {@link #REGISTRAR_COMPRAS} porque es
     * el momento en que el inventario cambia de verdad.
     */
    RECIBIR_COMPRAS,

    /**
     * Anular una compra ya recibida, devolviendo el stock. Aparte de
     * {@link #RECIBIR_COMPRAS} por la misma razón que {@link #ANULAR_VENTAS} está
     * aparte de vender: deshacer y hacer no son la misma capacidad, y anular puede
     * dejar variantes en stock negativo.
     */
    ANULAR_COMPRAS,

    /** Activar y desactivar usuarios. */
    GESTIONAR_USUARIOS
}
