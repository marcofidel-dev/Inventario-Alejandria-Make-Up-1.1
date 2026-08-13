package com.alejandriamakeup.pos.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.alejandriamakeup.pos.PosApplication;

/**
 * Los parámetros de la URL JDBC de SQLite fallan en silencio si están mal
 * escritos: esta prueba es la evidencia de que sí se aplicaron.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
class SqlitePragmaTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void foreignKeysEstaActivado() throws Exception {
        String valor = consultarPragma("foreign_keys");
        System.out.println("VERIFICACION PRAGMA foreign_keys => " + valor);
        assertThat(valor).isEqualTo("1");
    }

    @Test
    void journalModeEsWal() throws Exception {
        String valor = consultarPragma("journal_mode");
        System.out.println("VERIFICACION PRAGMA journal_mode => " + valor);
        assertThat(valor).isEqualToIgnoringCase("wal");
    }

    private String consultarPragma(String pragma) throws Exception {
        try (Connection conexion = dataSource.getConnection();
             Statement statement = conexion.createStatement();
             ResultSet resultado = statement.executeQuery("PRAGMA " + pragma)) {
            assertThat(resultado.next()).isTrue();
            return resultado.getString(1);
        }
    }
}
