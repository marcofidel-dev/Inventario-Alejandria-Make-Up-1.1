package com.alejandriamakeup.pos.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.alejandriamakeup.pos.PosApplication;

/**
 * Vigila que Flyway realmente corra, no que esté configurado.
 *
 * <p>La distinción importa porque ya falló una vez en silencio: el proyecto
 * declaraba {@code org.flywaydb:flyway-core} y {@code spring.flyway.enabled: true},
 * pero en Boot 4 la autoconfiguración se movió de {@code spring-boot-autoconfigure}
 * a módulos por tecnología. Sin {@code org.springframework.boot:spring-boot-flyway}
 * no existe {@code FlywayAutoConfiguration}, la propiedad no hace nada y las
 * migraciones simplemente no se aplican — sin un solo mensaje de error. Si alguien
 * "limpia" esa dependencia del pom, este test lo detiene.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
class FlywayMigracionesTest {

    /** Las 16 tablas de dominio de V2, sin contar Configuracion ni el historial. */
    private static final List<String> TABLAS_DE_DOMINIO = List.of(
            "usuario", "cliente", "marca", "categoria", "producto", "variante",
            "proveedor", "compra", "compra_item", "sesion_caja", "conteo_denominacion",
            "venta", "venta_item", "movimiento_inventario", "movimiento_caja", "consecutivo");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private org.springframework.context.ApplicationContext contexto;

    /**
     * La guarda de verdad contra la trampa del pom.
     *
     * <p>Los otros dos tests leen el estado de la base, y eso no basta:
     * {@code flyway_schema_history} sobrevive de corridas anteriores, así que
     * sobre una base ya migrada seguirían pasando aunque la autoconfiguración
     * hubiera desaparecido. El fallo reaparecería recién al agregar una V3, que
     * no se aplicaría y nadie se enteraría. Preguntar por el bean comprueba el
     * cableado y no el residuo.
     */
    @Test
    void laAutoconfiguracionDeFlywayEstaPresenteEnElContexto() {
        assertThat(contexto.getBeanNamesForType(org.flywaydb.core.Flyway.class))
                .withFailMessage("No hay bean Flyway en el contexto: falta "
                        + "org.springframework.boot:spring-boot-flyway en el pom. Con solo "
                        + "org.flywaydb:flyway-core no existe FlywayAutoConfiguration y las "
                        + "migraciones no corren, sin ningun error visible.")
                .isNotEmpty();

        System.out.println("VERIFICACION bean Flyway en el contexto => "
                + String.join(", ", contexto.getBeanNamesForType(org.flywaydb.core.Flyway.class)));
    }

    /**
     * Toda migración que existe en el classpath está aplicada en la base.
     *
     * <p>Esta es la afirmación que los tests de estado no pueden hacer: si mañana
     * se agrega una V3 y algo impide aplicarla, {@code flyway_schema_history}
     * seguiría mostrando V1 y V2 — todo en orden aparente — mientras la V3 nunca
     * corre. Comparar los archivos resueltos contra lo aplicado lo detecta.
     *
     * <p>Se recorre {@code info().all()} filtrando por {@code isApplied()} y no
     * {@code info().pending()}, porque lo segundo tiene un punto ciego que
     * comprobé: una migración por encima de un {@code spring.flyway.target}
     * configurado queda en estado {@code ABOVE_TARGET} y <strong>no</strong>
     * aparece entre las pendientes. Con {@code target=2} y una V3 en el
     * classpath, {@code pending()} devolvía vacío mientras la V3 no se había
     * aplicado — exactamente el fallo que este test debe atrapar.
     */
    @Test
    void todaMigracionDelClasspathEstaAplicada() {
        org.flywaydb.core.Flyway flyway = contexto.getBean(org.flywaydb.core.Flyway.class);

        var sinAplicar = Arrays.stream(flyway.info().all())
                .filter(info -> !info.getState().isApplied())
                .map(info -> info.getVersion() + " - " + info.getDescription()
                        + " [" + info.getState() + "]")
                .toList();

        var aplicadas = Arrays.stream(flyway.info().all())
                .filter(info -> info.getState().isApplied())
                .map(info -> String.valueOf(info.getVersion()))
                .toList();

        System.out.println("VERIFICACION migraciones aplicadas => " + aplicadas
                + ", sin aplicar => " + sinAplicar);
        assertThat(sinAplicar).isEmpty();
        assertThat(aplicadas).contains("1", "2");
    }

    @Test
    void flywayAplicoLasMigracionesYQuedaronRegistradas() throws Exception {
        List<String> aplicadas = new ArrayList<>();
        try (Connection conexion = dataSource.getConnection();
             Statement statement = conexion.createStatement();
             ResultSet filas = statement.executeQuery(
                     "SELECT version, description, success FROM flyway_schema_history "
                             + "WHERE version IS NOT NULL ORDER BY installed_rank")) {
            while (filas.next()) {
                aplicadas.add(filas.getString("version") + " (" + filas.getString("description")
                        + ", success=" + filas.getString("success") + ")");
                assertThat(filas.getString("success")).isEqualTo("1");
            }
        }

        System.out.println("VERIFICACION migraciones aplicadas => " + aplicadas);
        assertThat(aplicadas).hasSizeGreaterThanOrEqualTo(2);
        assertThat(aplicadas.get(0)).startsWith("1 ");
        assertThat(aplicadas.get(1)).startsWith("2 ");
    }

    /**
     * Ninguna fila apunta a una fila que no existe.
     *
     * <p>Está aquí por la V5, que <strong>reconstruye {@code compra}</strong> —una
     * tabla madre, referenciada por {@code compra_item} y por
     * {@code movimiento_inventario}— con un DROP y un CREATE. La secuencia se
     * verificó antes de escribirla, pero verificar una vez y confiar para siempre es
     * justo lo que este proyecto no hace: si una migración futura deja una FK
     * apuntando al vacío, {@code PRAGMA foreign_key_check} lo dice y ninguna otra
     * cosa lo diría, porque SQLite no revalida las FK existentes al recrear una
     * tabla.
     *
     * <p>El PRAGMA devuelve <em>una fila por cada violación</em>: vacío es que todo
     * está bien.
     */
    @Test
    void ningunaForeignKeyQuedoApuntandoAlVacio() throws Exception {
        List<String> violaciones = new ArrayList<>();
        try (Connection conexion = dataSource.getConnection();
             Statement statement = conexion.createStatement();
             ResultSet filas = statement.executeQuery("PRAGMA foreign_key_check")) {
            while (filas.next()) {
                violaciones.add(filas.getString(1) + " fila " + filas.getString(2)
                        + " -> " + filas.getString(3));
            }
        }

        System.out.println("VERIFICACION PRAGMA foreign_key_check => "
                + (violaciones.isEmpty() ? "limpio" : violaciones.toString()));
        assertThat(violaciones)
                .withFailMessage("Hay filas apuntando a filas que no existen: %s", violaciones)
                .isEmpty();
    }

    @Test
    void las16TablasDeDominioExisten() throws Exception {
        List<String> presentes = new ArrayList<>();
        try (Connection conexion = dataSource.getConnection();
             ResultSet tablas = conexion.getMetaData()
                     .getTables(null, null, "%", new String[] {"TABLE"})) {
            while (tablas.next()) {
                presentes.add(tablas.getString("TABLE_NAME"));
            }
        }

        System.out.println("VERIFICACION tablas de dominio presentes => "
                + TABLAS_DE_DOMINIO.stream().filter(presentes::contains).count() + "/" + TABLAS_DE_DOMINIO.size());
        assertThat(presentes).containsAll(TABLAS_DE_DOMINIO);
    }

    @Test
    void lasTresSeriesDeConsecutivosVienenSembradasPorLaMigracion() throws Exception {
        List<String> series = new ArrayList<>();
        try (Connection conexion = dataSource.getConnection();
             Statement statement = conexion.createStatement();
             ResultSet filas = statement.executeQuery(
                     "SELECT tipo, prefijo FROM consecutivo ORDER BY tipo")) {
            while (filas.next()) {
                series.add(filas.getString("tipo") + "=" + filas.getString("prefijo"));
            }
        }

        System.out.println("VERIFICACION series de consecutivos => " + series);
        assertThat(series).containsExactly("COMPRA=C", "SESION_CAJA=S", "VENTA=V");
    }
}
