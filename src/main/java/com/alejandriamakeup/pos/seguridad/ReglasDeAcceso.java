package com.alejandriamakeup.pos.seguridad;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import org.springframework.http.server.PathContainer;

/**
 * El único lugar donde se declara qué exige cada ruta de la API.
 *
 * <p>Centralizado a propósito, en vez de una anotación por método de controller:
 * así la política se lee de corrido en un archivo, y sobre todo se puede
 * <strong>auditar</strong>. {@code ReglasDeAccesoTest} enumera todos los handlers
 * que Spring tiene mapeados y exige que cada uno resuelva a una regla de aquí, de
 * modo que un endpoint nuevo sin regla rompe el build en vez de salir a
 * producción abierto.
 *
 * <p>Una ruta sin regla se <strong>niega</strong>. No hay comodín de "lo demás es
 * público", ni "lo demás basta con estar autenticado": olvidar declarar una ruta
 * tiene que doler en desarrollo, no filtrar datos en la tienda.
 */
@Component
public class ReglasDeAcceso {

    private record Entrada(HttpMethod metodo, PathPattern patron, Regla regla) {
    }

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    private final List<Entrada> entradas = new ArrayList<>();

    /**
     * Rutas permitidas cuando todavía no existe ningún usuario. Todo lo demás
     * responde 409 hasta que se cree la DUENA.
     */
    private final List<Entrada> configuracionInicial = new ArrayList<>();

    public ReglasDeAcceso() {
        // --- Infraestructura -------------------------------------------------
        publico(HttpMethod.GET, "/api/v1/health");

        // --- Autenticación ---------------------------------------------------
        publico(HttpMethod.GET, "/api/v1/auth/estado");
        publico(HttpMethod.GET, "/api/v1/auth/perfiles");
        publico(HttpMethod.POST, "/api/v1/auth/configuracion-inicial");
        publico(HttpMethod.POST, "/api/v1/auth/login");
        autenticado(HttpMethod.POST, "/api/v1/auth/logout");
        autenticado(HttpMethod.GET, "/api/v1/auth/sesion");

        // --- Respaldo --------------------------------------------------------
        requiere(HttpMethod.POST, "/api/v1/backup", Permiso.RESPALDAR);

        // --- Caja ------------------------------------------------------------
        // Las rutas literales van antes que las de variable: se resuelve por
        // especificidad, pero declararlas en este orden lo deja explícito.
        requiere(HttpMethod.GET, "/api/v1/caja/sesiones/actual", Permiso.OPERAR_CAJA);
        requiere(HttpMethod.GET, "/api/v1/caja/sesiones/sugerencia-apertura", Permiso.OPERAR_CAJA);
        requiere(HttpMethod.GET, "/api/v1/caja/sesiones", Permiso.OPERAR_CAJA);
        requiere(HttpMethod.POST, "/api/v1/caja/sesiones", Permiso.OPERAR_CAJA);
        requiere(HttpMethod.GET, "/api/v1/caja/sesiones/{id}", Permiso.OPERAR_CAJA);
        requiere(HttpMethod.GET, "/api/v1/caja/sesiones/{id}/movimientos", Permiso.OPERAR_CAJA);
        requiere(HttpMethod.POST, "/api/v1/caja/sesiones/{id}/cierre", Permiso.OPERAR_CAJA);
        requiere(HttpMethod.POST, "/api/v1/caja/movimientos", Permiso.REGISTRAR_MOVIMIENTO_CAJA);

        // Anotar una sesión no cambia ningún monto: agrega una explicación firmada.
        // Basta con poder operar caja, y de quién es la sesión se encarga el servicio.
        requiere(HttpMethod.GET, "/api/v1/caja/sesiones/{id}/notas", Permiso.OPERAR_CAJA);
        requiere(HttpMethod.POST, "/api/v1/caja/sesiones/{id}/notas", Permiso.OPERAR_CAJA);

        // --- Catálogo --------------------------------------------------------
        // La lectura del catálogo la necesita el punto de venta, así que basta con
        // estar autenticado. Los costos van aparte, con su propio permiso.
        autenticado(HttpMethod.GET, "/api/v1/catalogo");
        requiere(HttpMethod.GET, "/api/v1/catalogo/costos", Permiso.VER_COSTOS_Y_MARGENES);

        requiere(HttpMethod.POST, "/api/v1/catalogo/marcas", Permiso.EDITAR_CATALOGO);
        requiere(HttpMethod.PUT, "/api/v1/catalogo/marcas/{id}", Permiso.EDITAR_CATALOGO);
        requiere(HttpMethod.POST, "/api/v1/catalogo/marcas/{id}/desactivacion", Permiso.EDITAR_CATALOGO);
        requiere(HttpMethod.POST, "/api/v1/catalogo/marcas/{id}/reactivacion", Permiso.EDITAR_CATALOGO);

        requiere(HttpMethod.POST, "/api/v1/catalogo/categorias", Permiso.EDITAR_CATALOGO);
        requiere(HttpMethod.PUT, "/api/v1/catalogo/categorias/{id}", Permiso.EDITAR_CATALOGO);
        requiere(HttpMethod.POST, "/api/v1/catalogo/categorias/{id}/desactivacion", Permiso.EDITAR_CATALOGO);
        requiere(HttpMethod.POST, "/api/v1/catalogo/categorias/{id}/reactivacion", Permiso.EDITAR_CATALOGO);

        requiere(HttpMethod.POST, "/api/v1/catalogo/productos", Permiso.EDITAR_CATALOGO);
        requiere(HttpMethod.PUT, "/api/v1/catalogo/productos/{id}", Permiso.EDITAR_CATALOGO);
        requiere(HttpMethod.POST, "/api/v1/catalogo/productos/{id}/desactivacion", Permiso.EDITAR_CATALOGO);
        requiere(HttpMethod.POST, "/api/v1/catalogo/productos/{id}/reactivacion", Permiso.EDITAR_CATALOGO);

        requiere(HttpMethod.POST, "/api/v1/catalogo/variantes", Permiso.EDITAR_CATALOGO);
        requiere(HttpMethod.PUT, "/api/v1/catalogo/variantes/{id}", Permiso.EDITAR_CATALOGO);
        requiere(HttpMethod.POST, "/api/v1/catalogo/variantes/{id}/desactivacion", Permiso.EDITAR_CATALOGO);
        requiere(HttpMethod.POST, "/api/v1/catalogo/variantes/{id}/reactivacion", Permiso.EDITAR_CATALOGO);

        // --- Usuarios --------------------------------------------------------
        requiere(HttpMethod.GET, "/api/v1/usuarios", Permiso.GESTIONAR_USUARIOS);
        requiere(HttpMethod.POST, "/api/v1/usuarios/{id}/desactivacion", Permiso.GESTIONAR_USUARIOS);
        requiere(HttpMethod.POST, "/api/v1/usuarios/{id}/reactivacion", Permiso.GESTIONAR_USUARIOS);

        // --- Proveedores y compras -------------------------------------------
        // El modulo entero es de la DUENA. Ninguna ruta se queda en autenticado():
        // comprar revela lo que cuesta la mercancia, y FugaDeCostosTest barre todos
        // los GET de la API con sesion de EMPLEADA buscando exactamente eso. Un GET
        // de aqui sin permiso rompe el build, que es donde tiene que doler.
        requiere(HttpMethod.GET, "/api/v1/proveedores", Permiso.GESTIONAR_PROVEEDORES);
        requiere(HttpMethod.POST, "/api/v1/proveedores", Permiso.GESTIONAR_PROVEEDORES);
        requiere(HttpMethod.PUT, "/api/v1/proveedores/{id}", Permiso.GESTIONAR_PROVEEDORES);
        requiere(HttpMethod.POST, "/api/v1/proveedores/{id}/desactivacion", Permiso.GESTIONAR_PROVEEDORES);
        requiere(HttpMethod.POST, "/api/v1/proveedores/{id}/reactivacion", Permiso.GESTIONAR_PROVEEDORES);

        requiere(HttpMethod.GET, "/api/v1/compras", Permiso.REGISTRAR_COMPRAS);
        requiere(HttpMethod.POST, "/api/v1/compras", Permiso.REGISTRAR_COMPRAS);
        requiere(HttpMethod.GET, "/api/v1/compras/{id}", Permiso.REGISTRAR_COMPRAS);
        requiere(HttpMethod.PUT, "/api/v1/compras/{id}", Permiso.REGISTRAR_COMPRAS);
        requiere(HttpMethod.POST, "/api/v1/compras/{id}/descarte", Permiso.REGISTRAR_COMPRAS);

        // Recibir mueve inventario y revalua el costo: permiso propio.
        requiere(HttpMethod.GET, "/api/v1/compras/{id}/previa-recepcion", Permiso.RECIBIR_COMPRAS);
        requiere(HttpMethod.POST, "/api/v1/compras/{id}/recepcion", Permiso.RECIBIR_COMPRAS);

        // Anular puede dejar variantes en stock negativo: permiso aparte todavia.
        requiere(HttpMethod.GET, "/api/v1/compras/{id}/previa-anulacion", Permiso.ANULAR_COMPRAS);
        requiere(HttpMethod.POST, "/api/v1/compras/{id}/anulacion", Permiso.ANULAR_COMPRAS);

        // --- Configuración de la tienda --------------------------------------
        // Solo la DUENA. La EMPLEADA imprime recibos pero no redefine el NIT ni la
        // razón social que aparecen en todos los que se emitan después.
        requiere(HttpMethod.GET, "/api/v1/configuracion/tienda", Permiso.CONFIGURAR_TIENDA);
        requiere(HttpMethod.PUT, "/api/v1/configuracion/tienda", Permiso.CONFIGURAR_TIENDA);

        // --- Ventas ----------------------------------------------------------
        // La EMPLEADA vende: es su trabajo. Lo que no hace es anular, que devuelve
        // inventario y saca plata del cajón de hoy. Ninguna de las tres rutas se
        // queda en autenticado(): FugaDeCostosTest barre todos los GET de la API con
        // sesión de EMPLEADA, y venta_item guarda el costo congelado.
        requiere(HttpMethod.POST, "/api/v1/ventas", Permiso.REGISTRAR_VENTAS);
        // El listado del día lo necesita quien vende: es donde encuentra la venta que
        // hay que anular y, más adelante, el recibo que hay que reimprimir. Anular
        // sigue siendo otra cosa, y va abajo con su permiso.
        requiere(HttpMethod.GET, "/api/v1/ventas", Permiso.REGISTRAR_VENTAS);
        requiere(HttpMethod.GET, "/api/v1/ventas/sin-recibo", Permiso.REGISTRAR_VENTAS);
        requiere(HttpMethod.GET, "/api/v1/ventas/{id}", Permiso.REGISTRAR_VENTAS);
        requiere(HttpMethod.POST, "/api/v1/ventas/{id}/anulacion", Permiso.ANULAR_VENTAS);

        // Ver y regenerar el recibo van con REGISTRAR_VENTAS, o sea LOS DOS ROLES: la
        // EMPLEADA tiene que poder reimprimir el comprobante de su propia venta sin
        // buscar a nadie. Configurar la tienda sigue siendo otra cosa, arriba.
        requiere(HttpMethod.GET, "/api/v1/ventas/{id}/recibo", Permiso.REGISTRAR_VENTAS);
        requiere(HttpMethod.POST, "/api/v1/ventas/{id}/recibo", Permiso.REGISTRAR_VENTAS);

        // --- Inventario ------------------------------------------------------
        requiere(HttpMethod.POST, "/api/v1/inventario/carga-inicial", Permiso.CARGAR_INVENTARIO_INICIAL);
        requiere(HttpMethod.POST, "/api/v1/inventario/ajustes", Permiso.AJUSTAR_INVENTARIO);

        // --- Permitido durante la configuración inicial -----------------------
        enConfiguracionInicial(HttpMethod.GET, "/api/v1/health");
        enConfiguracionInicial(HttpMethod.GET, "/api/v1/auth/estado");
        // Devuelve [] cuando no hay nadie, que es más útil para la pantalla que un 409.
        enConfiguracionInicial(HttpMethod.GET, "/api/v1/auth/perfiles");
        enConfiguracionInicial(HttpMethod.POST, "/api/v1/auth/configuracion-inicial");

        entradas.sort(Comparator.comparing(Entrada::patron, PathPattern.SPECIFICITY_COMPARATOR));
    }

    /** La regla de una petición concreta, o vacío si nadie la declaró. */
    public Optional<Regla> para(HttpMethod metodo, String ruta) {
        PathContainer camino = PathContainer.parsePath(ruta);
        return entradas.stream()
                .filter(entrada -> entrada.metodo().equals(metodo))
                .filter(entrada -> entrada.patron().matches(camino))
                .map(Entrada::regla)
                .findFirst();
    }

    public boolean permitidaEnConfiguracionInicial(HttpMethod metodo, String ruta) {
        PathContainer camino = PathContainer.parsePath(ruta);
        return configuracionInicial.stream()
                .anyMatch(entrada -> entrada.metodo().equals(metodo)
                        && entrada.patron().matches(camino));
    }

    private void publico(HttpMethod metodo, String patron) {
        entradas.add(new Entrada(metodo, PARSER.parse(patron), new Regla.Publico()));
    }

    private void autenticado(HttpMethod metodo, String patron) {
        entradas.add(new Entrada(metodo, PARSER.parse(patron), new Regla.Autenticado()));
    }

    private void requiere(HttpMethod metodo, String patron, Permiso permiso) {
        entradas.add(new Entrada(metodo, PARSER.parse(patron), new Regla.RequierePermiso(permiso)));
    }

    private void enConfiguracionInicial(HttpMethod metodo, String patron) {
        configuracionInicial.add(new Entrada(metodo, PARSER.parse(patron), new Regla.Publico()));
    }
}
