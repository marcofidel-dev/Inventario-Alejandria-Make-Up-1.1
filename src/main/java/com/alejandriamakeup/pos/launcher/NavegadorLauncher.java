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

    /**
     * En ese orden: el primero instalado gana. En las tres el binario dentro de
     * {@code Contents/MacOS/} se llama exactamente igual que la carpeta sin
     * {@code .app} — confirmado corriendo {@code ls} sobre
     * {@code Google Chrome.app} en esta máquina; Edge y Brave no están
     * instalados aquí para confirmarlo igual, pero siguen la misma convención
     * de bundle en macOS.
     */
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
     * NUNCA {@code open -a "<App>" --args --app=<url>}. Probado en esta máquina
     * con Chrome ya abierto (con ventanas previas): {@code open} descarta
     * {@code --args} por completo cuando la app ya está corriendo, así que
     * Chrome solo pasa al frente con sus ventanas de siempre —nunca abre la
     * ventana en modo app— y sin embargo el proceso {@code open} termina con
     * éxito, así que {@link #lanzar} lo daría por bueno y el log diría
     * "Navegador abierto en modo app" mintiendo. Por eso se lanza el binario
     * del bundle directo, sin pasar por {@code open}: así sí abre una ventana
     * nueva en modo app aunque la app ya tenga ventanas abiertas, que es
     * justamente el caso de uso real —alguien que ya tenía Chrome abierto para
     * otra cosa y ahora prende el POS—.
     *
     * <p>Si algún día alguien "simplifica" esto de vuelta a {@code open -a}:
     * {@code NavegadorLauncherTest} falla, porque el comando esperado ya no
     * empieza con {@code open}.
     */
    private static List<List<String>> candidatosMac(String url, String home, Predicate<Path> existe) {
        List<List<String>> candidatos = new ArrayList<>();
        for (String app : APPS_MAC) {
            Path enSistema = Path.of("/Applications", app + ".app");
            Path enUsuario = Path.of(home, "Applications", app + ".app");
            Path bundle = existe.test(enSistema) ? enSistema : existe.test(enUsuario) ? enUsuario : null;
            if (bundle != null) {
                String binario = bundle.resolve("Contents/MacOS").resolve(app).toString();
                candidatos.add(List.of(binario, "--app=" + url));
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
