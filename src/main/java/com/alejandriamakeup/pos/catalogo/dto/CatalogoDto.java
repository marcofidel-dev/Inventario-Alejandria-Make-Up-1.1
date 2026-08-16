package com.alejandriamakeup.pos.catalogo.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Todo lo que el punto de venta necesita, en una sola llamada.
 *
 * <p>Se manda completo — activos e inactivos — porque la convención del proyecto es
 * cargar el catálogo entero al front y filtrar allí: en el mostrador la búsqueda
 * tiene que ser instantánea y una tienda de maquillaje cabe de sobra en memoria.
 *
 * <p><strong>Sin costos.</strong> Ni {@code costoPromedio} ni margen aparecen en
 * ningún nivel de esta estructura. Los costos salen por
 * {@code GET /api/v1/catalogo/costos}, que exige el permiso para verlos, y hay un
 * barrido automático que recorre todos los GET de la API con sesión de EMPLEADA
 * comprobando que nada se filtre.
 */
public record CatalogoDto(
        List<MarcaDto> marcas,
        List<CategoriaDto> categorias,
        List<ProductoDto> productos,
        List<VarianteDto> variantes) {

    public record MarcaDto(Long id, String nombre, boolean activo) {
    }

    public record CategoriaDto(Long id, String nombre, boolean activo) {
    }

    public record ProductoDto(
            Long id,
            String nombre,
            Long marcaId,
            Long categoriaId,
            String descripcion,
            boolean activo) {
    }

    /**
     * La variante con su stock.
     *
     * <p>{@code stock} es la suma del ledger, calculada en una consulta agrupada para
     * todas las variantes de una vez.
     *
     * <p><strong>{@code conHistorial} dice si la variante tiene algún movimiento.</strong>
     * Es lo que separa "existe y está agotada" de "nunca entró mercancía": las dos dan
     * stock 0 y no hay forma de distinguirlas mirando el número. El catálogo y el
     * buscador de venta solo listan las que tienen historial —no se puede vender lo que
     * nunca entró, y un producto sin costo real congelaría costo 0 en la venta—, mientras
     * que las pantallas donde la mercancía entra (compra y carga inicial) necesitan
     * justamente las otras, porque acaban de crearlas.
     *
     * <p><strong>{@code sinCosto} es una bandera, no un costo.</strong> Vale
     * {@code true} cuando el costo promedio está en cero, y con eso la pantalla de
     * venta rechaza la variante al agregarla al carrito en vez de al cobrar. No
     * publica ningún importe: el barrido de {@code FugaDeCostosTest} sigue corriendo
     * sobre el cuerpo entero buscando valores.
     *
     * <p><strong>{@code descripcion} viene armada del servidor</strong> por
     * {@code Descripcion.de()}, la misma función que congela {@code venta_item} y que
     * imprime el recibo. Va aquí y no se reconstruye en el front a propósito: dos
     * implementaciones de la misma cadena divergen algún día, y ese día el carrito
     * muestra una descripción y el recibo otra para la misma venta. Las partes sueltas
     * siguen viajando porque el catálogo las necesita en columnas y la búsqueda las
     * usa para su clave.
     */
    public record VarianteDto(
            Long id,
            Long productoId,
            String descripcion,
            String tono,
            String tamano,
            String codigoBarras,
            long precioVenta,
            int stockMinimo,
            long stock,
            boolean conHistorial,
            boolean sinCosto,
            LocalDate fechaVencimiento,
            Integer paoMeses,
            boolean activo) {
    }
}
