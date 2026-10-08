package com.alejandriamakeup.pos.launcher;

import java.awt.Desktop;
import java.awt.desktop.AppReopenedListener;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Abre el navegador en modo app (sin barra de direcciones) apuntando al
 * puerto local. Si no encuentra Chrome/Edge/Brave, cae a la apertura
 * estándar del sistema operativo.
 */
@Component
@ConditionalOnProperty(name = "app.launcher.enabled", havingValue = "true")
public class NavegadorLauncher {

    private static final Logger log = LoggerFactory.getLogger(NavegadorLauncher.class);

    /** Mismo orden de hoy: Chrome antes que Edge, LocalAppData antes que Program Files. */
    private static final String RELATIVA_CHROME = "Google\\Chrome\\Application\\chrome.exe";
    private static final String RELATIVA_EDGE = "Microsoft\\Edge\\Application\\msedge.exe";

    /** En ese orden: el primero instalado gana. */
    private static final List<String> APPS_MAC = List.of("Google Chrome", "Microsoft Edge", "Brave Browser");

    private final int puerto;

    public NavegadorLauncher(@Value("${server.port}") int puerto) {
        this.puerto = puerto;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void abrirNavegador() {
        abrir();
        registrarReaperturaDesdeElDock();
    }

    private void abrir() {
        String url = "http://localhost:" + puerto + "/";
        String osName = System.getProperty("os.name", "");

        for (List<String> candidato : candidatosModoApp(osName, url, System.getProperty("user.home"),
                System::getenv, Files::exists)) {
            if (lanzar(candidato)) {
                log.info("Navegador abierto en modo app con {}", candidato);
                return;
            }
        }

        if (esMac(osName) && lanzar(List.of("open", url))) {
            log.info("Navegador por defecto del sistema abierto con 'open {}'", url);
            return;
        }

        abrirConEscritorio(url);
    }

    /**
     * Pulsar el ícono del Dock mientras la app ya corre manda este evento. Sin esto,
     * cerrar la ventana del navegador y volver a hacer clic en el Dock no hace nada:
     * el proceso sigue vivo, pero no hay ninguna ventana que traer al frente.
     */
    private void registrarReaperturaDesdeElDock() {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop.getDesktop().addAppEventListener((AppReopenedListener) e -> {
                log.info("Reapertura solicitada desde el Dock");
                abrir();
            });
        } catch (UnsupportedOperationException e) {
            log.debug("Este equipo no soporta AppReopenedListener", e);
        }
    }

    private boolean lanzar(List<String> comando) {
        try {
            new ProcessBuilder(comando).start();
            return true;
        } catch (IOException e) {
            log.debug("No se pudo lanzar {}", comando, e);
            return false;
        }
    }

    /**
     * Pura: sin tocar el filesystem ni lanzar procesos, para poder probar las ramas
     * de Windows y macOS sin depender de qué haya instalado la máquina que corre la
     * suite. Devuelve los candidatos en orden de preferencia; quien llama los intenta
     * uno por uno hasta que alguno lance con éxito — igual que antes en Windows, que
     * ya probaba varios si el primero fallaba al lanzar (no solo al buscar).
     */
    static List<List<String>> candidatosModoApp(String osName, String url, String home,
            Function<String, String> getenv, Predicate<Path> existe) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return candidatosWindows(url, getenv, existe);
        }
        if (os.contains("mac")) {
            return candidatosMac(url, home, existe);
        }
        return List.of();
    }

    private static List<List<String>> candidatosWindows(String url, Function<String, String> getenv,
            Predicate<Path> existe) {
        String localAppData = getenv.apply("LocalAppData");
        String programFiles = getenv.apply("ProgramFiles");
        String programFilesX86 = getenv.apply("ProgramFiles(x86)");

        // List.of no admite null: no se puede envolver a los candidatos (varios
        // vienen vacíos si falta la variable de entorno) en un solo List.of, hay
        // que filtrar antes de construir la lista.
        List<String> ejecutables = new ArrayList<>();
        ejecutables.add(rutaSiExiste(localAppData, RELATIVA_CHROME, existe));
        ejecutables.add(rutaSiExiste(programFiles, RELATIVA_CHROME, existe));
        ejecutables.add(rutaSiExiste(programFilesX86, RELATIVA_CHROME, existe));
        ejecutables.add(rutaSiExiste(programFiles, RELATIVA_EDGE, existe));
        ejecutables.add(rutaSiExiste(programFilesX86, RELATIVA_EDGE, existe));

        List<List<String>> candidatos = new ArrayList<>();
        for (String ejecutable : ejecutables) {
            if (ejecutable != null) {
                candidatos.add(List.of(ejecutable, "--app=" + url));
            }
        }
        return candidatos;
    }

    /**
     * Sin {@code -n}: probado en esta máquina que {@code open -na "Calculator"} dos
     * veces deja dos procesos vivos (PID distinto cada vez), mientras que
     * {@code open -a "Calculator"} reutiliza el mismo proceso. {@code -n} fuerza una
     * instancia nueva aunque la app ya esté abierta — exactamente lo que no se
     * quiere para un navegador, que quedaría abriendo copias cada vez que alguien
     * cobra una venta con Chrome ya abierto de antes.
     */
    private static List<List<String>> candidatosMac(String url, String home, Predicate<Path> existe) {
        List<List<String>> candidatos = new ArrayList<>();
        for (String app : APPS_MAC) {
            if (existe.test(Path.of("/Applications", app + ".app"))
                    || existe.test(Path.of(home, "Applications", app + ".app"))) {
                candidatos.add(List.of("open", "-a", app, "--args", "--app=" + url));
            }
        }
        return candidatos;
    }

    private static String rutaSiExiste(String base, String relativo, Predicate<Path> existe) {
        if (base == null || base.isBlank()) {
            return null;
        }
        Path candidato = Path.of(base, relativo);
        return existe.test(candidato) ? candidato.toString() : null;
    }

    private static boolean esMac(String osName) {
        return osName.toLowerCase(Locale.ROOT).contains("mac");
    }

    private void abrirConEscritorio(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (IOException e) {
            log.warn("No se pudo abrir el navegador automáticamente", e);
        }
        log.warn("Abra el navegador manualmente en {}", url);
    }
}
