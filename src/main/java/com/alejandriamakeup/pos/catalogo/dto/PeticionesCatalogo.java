package com.alejandriamakeup.pos.catalogo.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Los cuerpos de entrada del catálogo, juntos porque se leen mejor comparados.
 *
 * <p><strong>Ninguno tiene {@code costoPromedio}.</strong> No es un olvido ni algo
 * que se filtre al mapear: el costo promedio se deriva de la carga inicial y de la
 * recepción de compras, y no hay forma de dictarlo desde afuera — tampoco para la
 * DUENA. Mandarlo en el JSON no hace nada, y hay un test que lo comprueba en vez de
 * confiar en que Jackson siga ignorando propiedades desconocidas.
 */
public final class PeticionesCatalogo {

    private PeticionesCatalogo() {
    }

    public record Marca(
            @NotBlank(message = "El nombre es obligatorio")
            @Size(max = 80, message = "El nombre no puede pasar de 80 caracteres")
            String nombre) {
    }

    public record Categoria(
            @NotBlank(message = "El nombre es obligatorio")
            @Size(max = 80, message = "El nombre no puede pasar de 80 caracteres")
            String nombre) {
    }

    public record Producto(
            @NotBlank(message = "El nombre es obligatorio")
            @Size(max = 120, message = "El nombre no puede pasar de 120 caracteres")
            String nombre,

            @NotNull(message = "La marca es obligatoria")
            Long marcaId,

            @NotNull(message = "La categoría es obligatoria")
            Long categoriaId,

            @Size(max = 500, message = "La descripción no puede pasar de 500 caracteres")
            String descripcion) {
    }

    /**
     * Una variante. El precio puede ser 0 solo si la variante está inactiva: una
     * variante activa con precio 0 se vendería gratis, y eso lo valida el servicio
     * porque el esquema admite {@code >= 0}.
     */
    public record Variante(
            @NotNull(message = "El producto es obligatorio")
            Long productoId,

            @Size(max = 60, message = "El tono no puede pasar de 60 caracteres")
            String tono,

            @Size(max = 40, message = "El tamaño no puede pasar de 40 caracteres")
            String tamano,

            @Size(max = 60, message = "El código de barras no puede pasar de 60 caracteres")
            String codigoBarras,

            @NotNull(message = "El precio de venta es obligatorio")
            @PositiveOrZero(message = "El precio de venta no puede ser negativo")
            Long precioVenta,

            @PositiveOrZero(message = "El stock mínimo no puede ser negativo")
            Integer stockMinimo,

            LocalDate fechaVencimiento,

            @Positive(message = "El PAO en meses debe ser positivo")
            Integer paoMeses) {
    }
}
