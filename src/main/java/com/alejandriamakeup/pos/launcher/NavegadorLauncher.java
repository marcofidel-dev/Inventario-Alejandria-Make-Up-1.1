package com.alejandriamakeup.pos.launcher;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Abre el navegador en modo app (sin barra de direcciones) apuntando al
 * puerto local. Si no encuentra Chrome/Edge, cae a la apertura estándar del
 * sistema operativo.
 */
@Component
@ConditionalOnProperty(name = "app.launcher.enabled", havingValue = "true")
public class NavegadorLauncher {

    private static final Logger log = LoggerFactory.getLogger(NavegadorLauncher.class);

    private final int puerto;

    public NavegadorLauncher(@Value("${server.port}") int puerto) {
        this.puerto = puerto;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void abrirNavegador() {
        String url = "http://localhost:" + puerto + "/";
        if (!intentarAbrirEnModoApp(url)) {
            abrirConEscritorio(url);
        }
    }

    private boolean intentarAbrirEnModoApp(String url) {
        for (String ejecutable : ejecutablesNavegador()) {
            if (ejecutable == null) {
                continue;
            }
            try {
                new ProcessBuilder(ejecutable, "--app=" + url).start();
                log.info("Navegador abierto en modo app con {}", ejecutable);
                return true;
            } catch (IOException e) {
                log.debug("No se pudo lanzar {}", ejecutable, e);
            }
        }
        return false;
    }

    private List<String> ejecutablesNavegador() {
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        String localAppData = System.getenv("LocalAppData");
        return Stream.of(
                        rutaSiExiste(localAppData, "Google\\Chrome\\Application\\chrome.exe"),
                        rutaSiExiste(programFiles, "Google\\Chrome\\Application\\chrome.exe"),
                        rutaSiExiste(programFilesX86, "Google\\Chrome\\Application\\chrome.exe"),
                        rutaSiExiste(programFiles, "Microsoft\\Edge\\Application\\msedge.exe"),
                        rutaSiExiste(programFilesX86, "Microsoft\\Edge\\Application\\msedge.exe")
                )
                .filter(Objects::nonNull)
                .toList();
    }

    private String rutaSiExiste(String base, String relativo) {
        if (base == null || base.isBlank()) {
            return null;
        }
        Path candidato = Path.of(base, relativo);
        return Files.exists(candidato) ? candidato.toString() : null;
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
