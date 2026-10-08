package com.alejandriamakeup.pos.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.alejandriamakeup.pos.config.AppPaths.Rutas;

/**
 * Las tres ramas de sistema operativo, con el {@code os.name}, el {@code APPDATA} y
 * el {@code home} inyectados: así se prueban sin que el resultado dependa de en qué
 * máquina corre la suite.
 */
class AppPathsTest {

    @Test
    void windowsUsaAppData(@TempDir Path home) {
        Path appData = home.resolve("AppData/Roaming");
        Rutas rutas = AppPaths.resolver(List.of(), "Windows 11", appData.toString(), home.toString());

        System.out.println("VERIFICACION Windows => " + rutas.directorioRaiz());
        assertThat(rutas.directorioRaiz()).isEqualTo(appData.resolve("AlejandriaMakeUp"));
    }

    @Test
    void windowsSinAppDataCaeAPuntoConfig(@TempDir Path home) {
        Rutas rutas = AppPaths.resolver(List.of(), "Windows 11", "", home.toString());

        System.out.println("VERIFICACION Windows sin APPDATA => " + rutas.directorioRaiz());
        assertThat(rutas.directorioRaiz()).isEqualTo(home.resolve(".config/AlejandriaMakeUp"));
    }

    @Test
    void macUsaApplicationSupport(@TempDir Path home) {
        Rutas rutas = AppPaths.resolver(List.of(), "Mac OS X", null, home.toString());

        System.out.println("VERIFICACION macOS => " + rutas.directorioRaiz());
        assertThat(rutas.directorioRaiz())
                .isEqualTo(home.resolve("Library/Application Support/AlejandriaMakeUp"));
    }

    @Test
    void macConDatosViejosEnConfigNoMigraNada(@TempDir Path home) throws IOException {
        Path vieja = home.resolve(".config/AlejandriaMakeUp");
        Files.createDirectories(vieja);
        Files.writeString(vieja.resolve("data.db"), "rastro-viejo");

        Rutas rutas = AppPaths.resolver(List.of(), "Mac OS X", null, home.toString());

        System.out.println("VERIFICACION macOS con rastro viejo => activa=" + rutas.directorioRaiz()
                + " vieja=" + vieja);
        // La ruta activa es la nueva convención de Mac, no la vieja: no se migra sola.
        assertThat(rutas.directorioRaiz())
                .isEqualTo(home.resolve("Library/Application Support/AlejandriaMakeUp"));
        assertThat(vieja.resolve("data.db")).exists();
    }

    @Test
    void linuxUsaConvencionXdg(@TempDir Path home) {
        Rutas rutas = AppPaths.resolver(List.of(), "Linux", null, home.toString());

        System.out.println("VERIFICACION Linux => " + rutas.directorioRaiz());
        assertThat(rutas.directorioRaiz()).isEqualTo(home.resolve(".config/AlejandriaMakeUp"));
    }

    @Test
    void perfilDevAgregaSubcarpetaSobreLaRaizDeSistema(@TempDir Path home) {
        Rutas rutas = AppPaths.resolver(List.of("dev"), "Mac OS X", null, home.toString());

        System.out.println("VERIFICACION perfil dev => " + rutas.directorioRaiz());
        assertThat(rutas.directorioRaiz())
                .isEqualTo(home.resolve("Library/Application Support/AlejandriaMakeUp/dev"));
    }

    @Test
    void perfilTestIgnoraElSistemaYUsaElTemporal(@TempDir Path home) {
        Rutas rutas = AppPaths.resolver(List.of("test"), "Mac OS X", null, home.toString());

        System.out.println("VERIFICACION perfil test => " + rutas.directorioRaiz());
        assertThat(rutas.directorioRaiz().toString()).contains("AlejandriaMakeUp-test");
    }

    @Test
    void crearDirectoriosCreaLasCuatroSubcarpetas(@TempDir Path home) {
        Rutas rutas = AppPaths.resolver(List.of(), "Mac OS X", null, home.toString());
        rutas.crearDirectorios();

        System.out.println("VERIFICACION subcarpetas => " + rutas.directorioRaiz());
        assertThat(rutas.directorioDatos()).exists();
        assertThat(rutas.directorioRecibos()).exists();
        assertThat(rutas.directorioBackups()).exists();
        assertThat(rutas.directorioLogs()).exists();
    }
}
