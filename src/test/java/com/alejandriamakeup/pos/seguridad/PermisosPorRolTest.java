package com.alejandriamakeup.pos.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.alejandriamakeup.pos.usuarios.Rol;

/**
 * Las prohibiciones de la EMPLEADA, una por una.
 *
 * <p>Estos son tests unitarios y no de HTTP a propósito. Cuatro de las cinco
 * prohibiciones son sobre módulos que aún no existen — catálogo, inventario,
 * métricas, ventas — y escribir hoy endpoints delgados solo para poder pedirles un
 * 403 sería trabajo desechable: cuando exista el módulo de catálogo, editar una
 * variante no será un {@code PUT /variantes/{id}/precio}. Los endpoints se
 * reescribirían y sus tests quedarían en verde probando un camino que ya no es el
 * real. {@link PermisosPorRol} sí es código permanente.
 *
 * <p>Los 403 por HTTP están donde hay algo de verdad que blindar: la caja, en
 * {@code CajaHttpTest} y {@code AutorizacionHttpTest}.
 */
class PermisosPorRolTest {

    /** Lo que la EMPLEADA no puede hacer, tal como lo define la Fase 2. */
    private static final Set<Permiso> PROHIBIDOS_A_LA_EMPLEADA = EnumSet.of(
            Permiso.VER_COSTOS_Y_MARGENES,
            Permiso.VER_METRICAS,
            Permiso.EDITAR_CATALOGO,
            Permiso.AJUSTAR_INVENTARIO,
            Permiso.ANULAR_VENTAS,
            Permiso.VER_SESIONES_DE_OTROS,
            // Fase 3: cargar el inventario es puesta en marcha, no operación diaria.
            Permiso.CARGAR_INVENTARIO_INICIAL,
            // Fase 3: quien pueda activar y desactivar usuarios puede darse permisos.
            Permiso.GESTIONAR_USUARIOS,
            // Fase 6: el modulo de compras entero es de la DUENA. Comprar revela lo
            // que cuesta la mercancia, que es la mitad de VER_COSTOS_Y_MARGENES
            // entrando por otra puerta.
            Permiso.GESTIONAR_PROVEEDORES,
            Permiso.REGISTRAR_COMPRAS,
            Permiso.RECIBIR_COMPRAS,
            Permiso.ANULAR_COMPRAS,
            // Fase 10: la EMPLEADA imprime recibos, pero no redefine el NIT ni la razón
            // social que salen impresos en todos los que se emitan después.
            Permiso.CONFIGURAR_TIENDA);

    @Test
    void laEmpleadaNoPuedeVerCostosNiMargenes() {
        assertThat(PermisosPorRol.puede(Rol.EMPLEADA, Permiso.VER_COSTOS_Y_MARGENES)).isFalse();
        assertThat(PermisosPorRol.puede(Rol.DUENA, Permiso.VER_COSTOS_Y_MARGENES)).isTrue();
    }

    @Test
    void laEmpleadaNoPuedeEntrarAMetricas() {
        assertThat(PermisosPorRol.puede(Rol.EMPLEADA, Permiso.VER_METRICAS)).isFalse();
        assertThat(PermisosPorRol.puede(Rol.DUENA, Permiso.VER_METRICAS)).isTrue();
    }

    @Test
    void laEmpleadaNoPuedeEditarPreciosNiCatalogo() {
        assertThat(PermisosPorRol.puede(Rol.EMPLEADA, Permiso.EDITAR_CATALOGO)).isFalse();
        assertThat(PermisosPorRol.puede(Rol.DUENA, Permiso.EDITAR_CATALOGO)).isTrue();
    }

    @Test
    void laEmpleadaNoPuedeAjustarInventario() {
        assertThat(PermisosPorRol.puede(Rol.EMPLEADA, Permiso.AJUSTAR_INVENTARIO)).isFalse();
        assertThat(PermisosPorRol.puede(Rol.DUENA, Permiso.AJUSTAR_INVENTARIO)).isTrue();
    }

    @Test
    void laEmpleadaNoPuedeAnularVentas() {
        assertThat(PermisosPorRol.puede(Rol.EMPLEADA, Permiso.ANULAR_VENTAS)).isFalse();
        assertThat(PermisosPorRol.puede(Rol.DUENA, Permiso.ANULAR_VENTAS)).isTrue();
    }

    @Test
    void laEmpleadaNoPuedeVerSesionesDeCajaDeOtros() {
        assertThat(PermisosPorRol.puede(Rol.EMPLEADA, Permiso.VER_SESIONES_DE_OTROS)).isFalse();
        assertThat(PermisosPorRol.puede(Rol.DUENA, Permiso.VER_SESIONES_DE_OTROS)).isTrue();
    }

    /**
     * Las cuatro capacidades de compras, juntas: el módulo entero es de la DUENA.
     * Se afirman en un solo test porque la regla es una sola —"la EMPLEADA no
     * compra"— y partirla en cuatro no diría nada más.
     */
    @Test
    void laEmpleadaNoTocaElModuloDeCompras() {
        for (Permiso permiso : List.of(Permiso.GESTIONAR_PROVEEDORES, Permiso.REGISTRAR_COMPRAS,
                Permiso.RECIBIR_COMPRAS, Permiso.ANULAR_COMPRAS)) {
            assertThat(PermisosPorRol.puede(Rol.EMPLEADA, permiso))
                    .withFailMessage("La EMPLEADA no debería tener %s", permiso)
                    .isFalse();
            assertThat(PermisosPorRol.puede(Rol.DUENA, permiso))
                    .withFailMessage("La DUENA debería tener %s", permiso)
                    .isTrue();
        }
    }

    /**
     * La pareja que define la Fase 10: imprimir el comprobante sí, cambiar lo que dice
     * su encabezado no. Son la misma pantalla para quien mira desde afuera y dos
     * capacidades distintas para el sistema.
     */
    @Test
    void laEmpleadaImprimeRecibosPeroNoConfiguraLaTienda() {
        assertThat(PermisosPorRol.puede(Rol.EMPLEADA, Permiso.REGISTRAR_VENTAS)).isTrue();
        assertThat(PermisosPorRol.puede(Rol.EMPLEADA, Permiso.CONFIGURAR_TIENDA)).isFalse();
        assertThat(PermisosPorRol.puede(Rol.DUENA, Permiso.CONFIGURAR_TIENDA)).isTrue();
    }

    @Test
    void laEmpleadaSiPuedeOperarCajaYRegistrarMovimientos() {
        assertThat(PermisosPorRol.puede(Rol.EMPLEADA, Permiso.OPERAR_CAJA)).isTrue();
        assertThat(PermisosPorRol.puede(Rol.EMPLEADA, Permiso.REGISTRAR_MOVIMIENTO_CAJA)).isTrue();
    }

    @Test
    void laDuenaPuedeTodo() {
        assertThat(PermisosPorRol.de(Rol.DUENA)).containsExactlyInAnyOrder(Permiso.values());
    }

    /**
     * La red que hace que un permiso nuevo no se cuele.
     *
     * <p>{@link PermisosPorRol} define a la EMPLEADA como lista de permitidos, así
     * que un {@link Permiso} agregado mañana le queda negado por omisión. Este test
     * exige además que la decisión sea consciente: si aparece un permiso que no está
     * ni en los concedidos ni en esta lista de prohibidos, falla y obliga a
     * clasificarlo.
     */
    @Test
    void cadaPermisoEstaClasificadoParaLaEmpleada() {
        Set<Permiso> concedidos = PermisosPorRol.de(Rol.EMPLEADA);

        for (Permiso permiso : Permiso.values()) {
            boolean concedido = concedidos.contains(permiso);
            boolean prohibido = PROHIBIDOS_A_LA_EMPLEADA.contains(permiso);

            assertThat(concedido || prohibido)
                    .withFailMessage("El permiso %s no está clasificado para la EMPLEADA. "
                            + "Hay que decidir si lo tiene (PermisosPorRol) o no "
                            + "(PROHIBIDOS_A_LA_EMPLEADA en este test).", permiso)
                    .isTrue();
            assertThat(concedido && prohibido)
                    .withFailMessage("El permiso %s está concedido y prohibido a la vez.", permiso)
                    .isFalse();
        }

        System.out.println("VERIFICACION permisos de EMPLEADA => concedidos " + concedidos
                + ", prohibidos " + PROHIBIDOS_A_LA_EMPLEADA);
        assertThat(concedidos).doesNotContainAnyElementsOf(PROHIBIDOS_A_LA_EMPLEADA);
    }
}
