package com.alejandriamakeup.pos.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.catalogo.dto.PeticionesCatalogo;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * Nombres duplicados: los cuatro casos, por las dos vías.
 *
 * <p>La distinción entre las dos vías es el corazón del diseño:
 *
 * <ul>
 *   <li><strong>Por el servicio</strong> sale un 409 que nombra el registro con el que
 *       se choca. Es lo que ve la usuaria, y es lo único que le permite entender qué
 *       pasó cuando escribió "Loréal" y ya existía "LOREAL".
 *   <li><strong>Por el repositorio</strong>, salteándose el servicio, salta el índice.
 *       Es la integridad de verdad: funciona aunque alguien inserte por otra vía, y es
 *       lo que hace que la regla sea un invariante y no una costumbre.
 * </ul>
 *
 * <p>Los cuatro casos incluyen los tres que {@code COLLATE NOCASE} dejaba pasar. Está
 * comprobado sobre SQLite 3.53.2 que NOCASE solo pliega ASCII: acepta 'Niña' contra
 * 'NIÑA', 'Loréal' contra 'LORÉAL' y 'Máybelline' contra 'maybelline'. Para una tienda
 * cuyas marcas se llaman L'Oréal, Lancôme y Estée Lauder, eso no es una red.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
class NombresDuplicadosTest {

    private static final String URL = BaseDatosAislada.urlNueva("nombres-duplicados");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @Autowired
    private ServicioMarca servicioMarca;

    @Autowired
    private ServicioCategoria servicioCategoria;

    @Autowired
    private ServicioProducto servicioProducto;

    @Autowired
    private MarcaRepository marcaRepository;

    @Autowired
    private DataSource dataSource;

    // ------------------------------------------------------- por el servicio

    @Test
    void elServicioRechazaLosCuatroCasosYDiceConQueChoca() {
        String[][] casos = {
            {"MAC", "Mac"},
            {"Niña", "NIÑA"},
            {"Loréal", "LORÉAL"},
            {"Máybelline", "maybelline"},
        };

        for (String[] caso : casos) {
            String original = caso[0] + " " + System.nanoTime();
            String duplicado = caso[1] + original.substring(caso[0].length());

            servicioMarca.crear(original);

            System.out.println("VERIFICACION servicio: '" + original + "' vs '" + duplicado + "'");
            assertThatThrownBy(() -> servicioMarca.crear(duplicado))
                    .isInstanceOf(ErrorDeAplicacion.class)
                    .hasMessageContaining(original)
                    .hasMessageContaining("salvo tildes o mayúsculas");
        }
    }

    @Test
    void nombresDeVerdadDistintosSiPasan() {
        assertThatCode(() -> {
            servicioMarca.crear("Mac Cosmetics");
            servicioMarca.crear("Mach Cosmetics");
            servicioMarca.crear("Nina Ricci");
            servicioMarca.crear("Niña Bonita");
        }).doesNotThrowAnyException();
    }

    @Test
    void laCategoriaTambienSeCompruebaNormalizando() {
        servicioCategoria.crear("Máscaras de pestañas");

        assertThatThrownBy(() -> servicioCategoria.crear("MASCARAS DE PESTANAS"))
                .isInstanceOf(ErrorDeAplicacion.class)
                .hasMessageContaining("Máscaras de pestañas");
    }

    /**
     * El producto es el único cuya clave lleva la marca. Dos marcas pueden vender cada
     * una su "Labial mate" — en cosmética los nombres genéricos abundan — pero una
     * marca no puede tener dos.
     */
    @Test
    void elProductoSeCompruebaDentroDeSuMarca() {
        Marca maybelline = servicioMarca.crear("Maybelline " + System.nanoTime());
        Marca essence = servicioMarca.crear("Essence " + System.nanoTime());
        Categoria labios = servicioCategoria.crear("Labios " + System.nanoTime());

        servicioProducto.crear(new PeticionesCatalogo.Producto(
                "Labial mate", maybelline.getId(), labios.getId(), null));

        System.out.println("VERIFICACION mismo producto en otra marca => debe permitirse");
        assertThatCode(() -> servicioProducto.crear(new PeticionesCatalogo.Producto(
                "Labial mate", essence.getId(), labios.getId(), null)))
                .doesNotThrowAnyException();

        System.out.println("VERIFICACION mismo producto en la misma marca => debe rechazarse");
        assertThatThrownBy(() -> servicioProducto.crear(new PeticionesCatalogo.Producto(
                "LABIAL MATE", maybelline.getId(), labios.getId(), null)))
                .isInstanceOf(ErrorDeAplicacion.class)
                .hasMessageContaining("Labial mate");
    }

    // -------------------------------------------------------- por el índice

    /**
     * Salteándose el servicio. Si esto pasara, la unicidad sería una costumbre del
     * código y no una propiedad de los datos.
     */
    @Test
    void elIndiceRechazaLosCuatroCasosAunqueNadieCompruebeAntes() {
        String[][] casos = {
            {"MAC", "Mac"},
            {"Niña", "NIÑA"},
            {"Loréal", "LORÉAL"},
            {"Máybelline", "maybelline"},
        };

        for (String[] caso : casos) {
            String sufijo = " " + System.nanoTime();
            marcaRepository.saveAndFlush(marcaCon(caso[0] + sufijo));

            System.out.println("VERIFICACION índice: '" + caso[0] + sufijo + "' vs '"
                    + caso[1] + sufijo + "'");
            assertThatThrownBy(() -> marcaRepository.saveAndFlush(marcaCon(caso[1] + sufijo)))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("ux_marca_nombre");
        }
    }

    /**
     * El normalizador de Java y la expresión del índice tienen que decir lo mismo, o el
     * servicio aprobaría cosas que el índice rechaza y saldrían 500 en vez de 409.
     *
     * <p>La comparación va sobre nombres reales de cosmética. El límite conocido es
     * fuera de Latin-1 — la 'ō' de Shiseidō — donde Java pliega y el SQL no; esa
     * dirección es la segura y está documentada en la migración.
     */
    @Test
    void elNormalizadorDeJavaCoincideConLaExpresionDelIndice() throws Exception {
        List<String> nombres = List.of(
                "MAC", "Mac", "Niña", "NIÑA", "Loréal", "LORÉAL", "L'Oréal",
                "Máybelline", "maybelline", "Lancôme", "LANCÔME", "Estée Lauder",
                "Kérastase", "Bourjois", "Garnier", "Nyx", "Böhm", "Rojo Mate");

        List<String> discrepancias = new java.util.ArrayList<>();
        try (Connection conexion = dataSource.getConnection();
             Statement statement = conexion.createStatement()) {

            for (String nombre : nombres) {
                String enSql = normalizarConElIndice(statement, nombre);
                String enJava = NombreNormalizado.de(nombre);
                if (!enSql.equals(enJava)) {
                    discrepancias.add(nombre + ": sql=" + enSql + " java=" + enJava);
                }
            }
        }

        System.out.println("VERIFICACION " + nombres.size()
                + " nombres normalizados por las dos vías => discrepancias: "
                + (discrepancias.isEmpty() ? "ninguna" : discrepancias));
        assertThat(discrepancias).isEmpty();
    }

    /**
     * Extrae del propio {@code sqlite_master} la expresión con la que se creó el índice
     * y la aplica al nombre. Así el test compara contra lo que hay en la base y no
     * contra una copia de la expresión, que podría quedar desfasada.
     */
    private String normalizarConElIndice(Statement statement, String nombre) throws Exception {
        String definicion;
        try (ResultSet r = statement.executeQuery(
                "SELECT sql FROM sqlite_master WHERE type='index' AND name='ux_marca_nombre'")) {
            r.next();
            definicion = r.getString(1);
        }

        String expresion = definicion.substring(definicion.indexOf('(') + 1, definicion.lastIndexOf(')'))
                .replace("nombre", "'" + nombre.replace("'", "''") + "'");

        try (ResultSet r = statement.executeQuery("SELECT " + expresion)) {
            r.next();
            return r.getString(1);
        }
    }

    private Marca marcaCon(String nombre) {
        Marca marca = new Marca();
        marca.setNombre(nombre);
        marca.setActivo(true);
        return marca;
    }
}
