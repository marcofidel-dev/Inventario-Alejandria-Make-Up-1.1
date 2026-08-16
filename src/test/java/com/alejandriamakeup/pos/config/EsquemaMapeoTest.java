package com.alejandriamakeup.pos.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.alejandriamakeup.pos.PosApplication;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.EntityType;

/**
 * Que este test arranque ya es la mitad de lo que comprueba: con
 * {@code ddl-auto: validate}, si una sola columna de una sola entidad no calza
 * contra el esquema, el contexto no se levanta y toda la suite cae.
 *
 * <p>Lo que añade es un mensaje útil cuando falta una entidad entera, que
 * {@code validate} no detecta — solo valida lo que está mapeado, no se queja de
 * una tabla que nadie mapeó.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
class EsquemaMapeoTest {

    /**
     * La lista es cerrada a propósito: agregar una entidad obliga a declararla aquí,
     * y eso es una decisión consciente y no un descubrimiento en producción.
     */
    private static final List<String> ENTIDADES_ESPERADAS = List.of(
            "Usuario", "Cliente", "Marca", "Categoria", "Producto", "Variante",
            "Proveedor", "Compra", "CompraItem", "SesionCaja", "ConteoDenominacion",
            "Venta", "VentaItem", "MovimientoInventario", "MovimientoCaja", "Consecutivo",
            // V3, Fase 2
            "IntentoLogin",
            // V6, Fase 7: las explicaciones de un arqueo se agregan, no editan la sesión
            "NotaSesionCaja",
            // V1, mapeada en la Fase 10: la tabla existía desde la primera migración y
            // no la usaba nadie hasta que el encabezado del recibo necesitó saber cómo
            // se llama la tienda.
            "Configuracion");

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private org.springframework.context.ApplicationContext contexto;

    @Autowired
    private DataSource dataSource;

    @Test
    void todasLasEntidadesDeDominioEstanMapeadas() {
        List<String> mapeadas = entityManager.getMetamodel().getEntities().stream()
                .map(EntityType::getName)
                .sorted()
                .toList();

        System.out.println("VERIFICACION entidades mapeadas (" + mapeadas.size() + ") => " + mapeadas);
        assertThat(mapeadas).containsAll(ENTIDADES_ESPERADAS);
        assertThat(mapeadas).hasSize(ENTIDADES_ESPERADAS.size());
    }

    /**
     * El stock es la suma del ledger, así que ninguna tabla debe tener una columna
     * de stock que alguien pudiera empezar a actualizar "para que sea más rápido".
     * {@code variante.stock_minimo} es la excepción legítima: es un umbral
     * configurado, no una existencia.
     */
    @Test
    void ningunaTablaTieneColumnaDeStock() throws Exception {
        List<String> sospechosas = new ArrayList<>();
        List<String> encontradas = new ArrayList<>();
        try (Connection conexion = dataSource.getConnection();
             ResultSet columnas = conexion.getMetaData().getColumns(null, null, "%", "%stock%")) {
            while (columnas.next()) {
                String columna = columnas.getString("COLUMN_NAME");
                String cualificada = columnas.getString("TABLE_NAME") + "." + columna;
                encontradas.add(cualificada);
                if (!"stock_minimo".equals(columna)) {
                    sospechosas.add(cualificada);
                }
            }
        }

        System.out.println("VERIFICACION columnas con 'stock' en el esquema => " + encontradas);
        // Sin esto el test pasaría por vacuidad: si las tablas no existieran, el
        // barrido no encontraría nada y "ninguna columna de stock" sería cierto
        // por la razón equivocada. Exigir el stock_minimo legítimo prueba que el
        // barrido llegó al esquema.
        assertThat(encontradas)
                .withFailMessage("El barrido no encontró variante.stock_minimo: no está mirando el esquema real")
                .contains("variante.stock_minimo");
        assertThat(sospechosas).isEmpty();
    }

    @Test
    void hayUnRepositorioPorCadaAgregado() {
        List<String> repositorios = List.of(contexto.getBeanNamesForType(
                        org.springframework.data.jpa.repository.JpaRepository.class)).stream()
                .sorted()
                .toList();

        System.out.println("VERIFICACION repositorios detectados (" + repositorios.size() + ") => " + repositorios);
        assertThat(repositorios).hasSizeGreaterThanOrEqualTo(ENTIDADES_ESPERADAS.size());
    }
}
