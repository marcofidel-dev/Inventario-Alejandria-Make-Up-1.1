package com.alejandriamakeup.pos.launcher;

import static com.alejandriamakeup.pos.launcher.NavegadorLauncher.candidatosModoApp;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@code candidatosModoApp} es pura: ni toca el filesystem real ni lanza procesos.
 * El predicado "existe" se simula con un {@code Set} en memoria, así el resultado
 * no depende de qué tenga instalado la máquina que corre la suite.
 */
class NavegadorLauncherTest {

    private static final String URL = "http://localhost:8080/";

    @Test
    void windowsConChromeEnLocalAppData() {
        Path chrome = Path.of("C:\\Users\\alguien\\AppData\\Local", "Google\\Chrome\\Application\\chrome.exe");
        Set<Path> instalados = Set.of(chrome);
        Map<String, String> env = Map.of("LocalAppData", "C:\\Users\\alguien\\AppData\\Local");

        List<List<String>> candidatos = candidatosModoApp("Windows 11", URL, "C:\\Users\\alguien",
                env::get, instalados::contains);

        System.out.println("VERIFICACION windows chrome => " + candidatos);
        assertThat(candidatos).containsExactly(List.of(chrome.toString(), "--app=" + URL));
    }

    @Test
    void windowsSinNadaInstaladoNoDaCandidatos() {
        List<List<String>> candidatos = candidatosModoApp("Windows 11", URL, "C:\\Users\\alguien",
                nombre -> null, ruta -> false);

        System.out.println("VERIFICACION windows sin nada => " + candidatos);
        assertThat(candidatos).isEmpty();
    }

    /**
     * NUNCA {@code open -a "<App>" --args ...}: con la app ya abierta, {@code open}
     * descarta {@code --args} y solo trae las ventanas existentes al frente — nunca
     * abre en modo app — y aun así termina con éxito, así que el launcher lo daría
     * por bueno en silencio. Si alguien "simplifica" {@code candidatosMac} de vuelta
     * a {@code open -a}, esta aserción falla porque el comando ya no empieza con
     * {@code open}.
     */
    @Test
    void macConSoloBraveInstaladoUsaBrave() {
        Path brave = Path.of("/Applications/Brave Browser.app");
        Set<Path> instalados = Set.of(brave);

        List<List<String>> candidatos = candidatosModoApp("Mac OS X", URL, "/Users/alguien",
                nombre -> null, instalados::contains);

        System.out.println("VERIFICACION mac brave => " + candidatos);
        assertThat(candidatos).containsExactly(List.of(
                "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser", "--app=" + URL));
    }

    @Test
    void macConChromeYEdgeInstaladosPrefiereChrome() {
        Path chrome = Path.of("/Applications/Google Chrome.app");
        Path edge = Path.of("/Applications/Microsoft Edge.app");
        Set<Path> instalados = Set.of(chrome, edge);

        List<List<String>> candidatos = candidatosModoApp("Mac OS X", URL, "/Users/alguien",
                nombre -> null, instalados::contains);

        System.out.println("VERIFICACION mac chrome+edge => " + candidatos);
        assertThat(candidatos.get(0)).containsExactly(
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "--app=" + URL);
        assertThat(candidatos).hasSize(2);
    }

    @Test
    void macEnCarpetaDeUsuarioTambienCuenta() {
        Path chromeDeUsuario = Path.of("/Users/alguien/Applications/Google Chrome.app");
        Set<Path> instalados = Set.of(chromeDeUsuario);

        List<List<String>> candidatos = candidatosModoApp("Mac OS X", URL, "/Users/alguien",
                nombre -> null, instalados::contains);

        System.out.println("VERIFICACION mac ~/Applications => " + candidatos);
        assertThat(candidatos).containsExactly(List.of(
                "/Users/alguien/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "--app=" + URL));
    }

    @Test
    void macSinNadaInstaladoNoDaCandidatos() {
        List<List<String>> candidatos = candidatosModoApp("Mac OS X", URL, "/Users/alguien",
                nombre -> null, ruta -> false);

        System.out.println("VERIFICACION mac sin nada => " + candidatos);
        assertThat(candidatos).isEmpty();
    }

    @Test
    void linuxNuncaDaCandidatos() {
        List<List<String>> candidatos = candidatosModoApp("Linux", URL, "/home/alguien",
                nombre -> "cualquier-cosa", ruta -> true);

        System.out.println("VERIFICACION linux => " + candidatos);
        assertThat(candidatos).isEmpty();
    }
}
