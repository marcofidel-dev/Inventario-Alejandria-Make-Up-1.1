package com.alejandriamakeup.pos.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.PosApplication;

/**
 * {@code ux_variante_combinacion} va sobre expresión
 * ({@code producto_id, COALESCE(tono,''), COALESCE(tamano,'')}) y no sobre las
 * columnas desnudas.
 *
 * <p>El caso que motivó el cambio es el primer test: en SQLite los NULL son
 * distintos entre sí en un índice único, así que un índice sobre
 * {@code (producto_id, tono, tamano)} aceptaba dos variantes del mismo producto
 * con tono y tamaño nulos — el producto sin variantes diferenciadas, que es el
 * más común del catálogo. El COALESCE los aplana a cadena vacía para que
 * colisionen.
 *
 * <p>Ojo con el tipo de excepción, que va a importar cuando se escriba el manejo
 * de errores de la API: en SQLite una violación de índice único <strong>no</strong>
 * llega como {@link org.springframework.dao.DataIntegrityViolationException}. El
 * driver de xerial lanza un {@code SQLiteException} genérico que Hibernate no
 * reconoce como violación de integridad, así que el traductor de Spring la
 * envuelve en {@code JpaSystemException}. Por eso aquí se afirma sobre
 * {@link DataAccessException} — la superclase común — y sobre el nombre del
 * índice en el mensaje, que es lo que de verdad identifica qué regla se rompió.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
@Transactional
class VarianteUnicidadTest {

    @Autowired
    private VarianteRepository varianteRepository;

    @Autowired
    private ProductoRepository productoRepository;

    @Autowired
    private MarcaRepository marcaRepository;

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Test
    void dosVariantesDelMismoProductoConTonoYTamanoNulosNoSonPermitidas() {
        Producto producto = nuevoProducto();
        varianteRepository.saveAndFlush(variante(producto, null, null));

        System.out.println("VERIFICACION insertando una segunda variante con tono=NULL y tamano=NULL "
                + "sobre el producto " + producto.getId());
        assertThatThrownBy(() -> varianteRepository.saveAndFlush(variante(producto, null, null)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ux_variante_combinacion");
    }

    @Test
    void dosVariantesDelMismoProductoConElMismoTonoNoSonPermitidas() {
        Producto producto = nuevoProducto();
        varianteRepository.saveAndFlush(variante(producto, "Rojo", "5 ml"));

        assertThatThrownBy(() -> varianteRepository.saveAndFlush(variante(producto, "Rojo", "5 ml")))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ux_variante_combinacion");
    }

    /** Que el COALESCE no vuelva iguales combinaciones que sí son distintas. */
    @Test
    void variantesConTonosDistintosConvivenSinProblema() {
        Producto producto = nuevoProducto();

        assertThatCode(() -> {
            varianteRepository.saveAndFlush(variante(producto, null, null));
            varianteRepository.saveAndFlush(variante(producto, "Rojo", null));
            varianteRepository.saveAndFlush(variante(producto, null, "5 ml"));
            varianteRepository.saveAndFlush(variante(producto, "Rojo", "5 ml"));
        }).doesNotThrowAnyException();

        assertThat(varianteRepository.findByProductoId(producto.getId())).hasSize(4);
    }

    /** La fecha de vencimiento es LocalDate: se guarda sin hora. */
    @Test
    void laFechaDeVencimientoSeGuardaComoFechaSinHora() {
        Producto producto = nuevoProducto();
        Variante variante = variante(producto, "Con vencimiento", null);
        variante.setFechaVencimiento(LocalDate.of(2027, 3, 1));
        Long id = varianteRepository.saveAndFlush(variante).getId();

        Variante releida = varianteRepository.findById(id).orElseThrow();
        System.out.println("VERIFICACION fecha_vencimiento releida => " + releida.getFechaVencimiento());
        assertThat(releida.getFechaVencimiento()).isEqualTo(LocalDate.of(2027, 3, 1));
    }

    private Variante variante(Producto producto, String tono, String tamano) {
        Variante variante = new Variante();
        variante.setProducto(producto);
        variante.setTono(tono);
        variante.setTamano(tamano);
        variante.setPrecioVenta(38900);
        variante.setCostoPromedio(21000);
        variante.setFechaCreacion(LocalDateTime.now());
        return variante;
    }

    private Producto nuevoProducto() {
        String sufijo = UUID.randomUUID().toString();

        Marca marca = new Marca();
        marca.setNombre("Marca " + sufijo);
        marca = marcaRepository.save(marca);

        Categoria categoria = new Categoria();
        categoria.setNombre("Categoria " + sufijo);
        categoria = categoriaRepository.save(categoria);

        Producto producto = new Producto();
        producto.setNombre("Producto " + sufijo);
        producto.setMarca(marca);
        producto.setCategoria(categoria);
        producto.setFechaCreacion(LocalDateTime.now());
        return productoRepository.save(producto);
    }
}
