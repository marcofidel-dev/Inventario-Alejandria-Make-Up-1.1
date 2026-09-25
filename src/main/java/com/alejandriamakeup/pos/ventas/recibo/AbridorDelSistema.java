package com.alejandriamakeup.pos.ventas.recibo;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * Abre un archivo con el programa que el sistema tenga asociado.
 *
 * <p>Existe para el recibo: la aplicación corre dentro de un navegador en modo app,
 * sin barra de direcciones, y ahí un PDF servido por HTTP abriría una ventana de
 * navegador suelta que después hay que cerrar a mano. Con el visor del sistema se
 * obtiene lo que se busca en el mostrador —ver e imprimir— y la ventana de cobro no
 * se toca.
 *
 * <p><strong>Fuera de toda transacción.</strong> Lanzar un proceso tarda bastante más
 * que una consulta, y el pool tiene una sola conexión: hacerlo dentro de un método
 * {@code @Transactional} la retendría mientras arranca un programa de escritorio. Por
 * eso este componente no toca la base y quien lo llama resuelve la ruta antes.
 *
 * <p><strong>Sin escritorio no es un error del programa.</strong> En un servidor sin
 * sesión gráfica —o en las pruebas— {@code Desktop} no está disponible. Se responde
 * un error con código propio para que la pantalla pueda decir dónde quedó el archivo
 * en vez de mostrar un 500 que no ayuda a nadie.
 */
@Component
public class AbridorDelSistema {

    private static final Logger log = LoggerFactory.getLogger(AbridorDelSistema.class);

    public void abrir(Path archivo) {
        String ruta = archivo.toAbsolutePath().toString();
        log.info("Abriendo recibo: {}", ruta);
        try {
            if (esWindows()) {
                abrirConStart(ruta);
            } else {
                abrirConDesktop(archivo);
            }
            log.info("Recibo entregado al sistema para abrir: {}", ruta);
        } catch (Exception e) {
            // Sin esto la excepción se perdería en el 409 y en consola no quedaría rastro.
            log.error("No se pudo abrir el recibo {}", ruta, e);
            copiarRutaAlPortapapeles(ruta);
            throw ErrorDeAplicacion.conflicto("SIN_VISOR",
                    "No se pudo abrir el PDF con el visor del sistema. El archivo está en "
                            + ruta + " (la ruta quedó copiada al portapapeles) y se puede abrir a mano.");
        }
    }

    private static boolean esWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * HIPÓTESIS SIN CONFIRMAR: la ventana del visor a veces no llega al frente.
     * Desktop.open y start terminan sin error y el log dice que se entregó, pero
     * Windows restringe qué procesos pueden pasar una ventana al frente, y la app
     * en modo navegador puede quedarse encima. Síntoma a buscar: el visor aparece
     * en la barra de tareas y no pasa adelante. No se resuelve hasta verlo fallar;
     * si pasa, el paso siguiente es traer la ventana al frente (p. ej. con powershell).
     *
     * <p>Lo mismo que el doble clic del Explorador. {@code start ""}: el primer argumento
     * entre comillas es el título de la ventana; sin él, una ruta con espacios se
     * confunde con el título y no abre nada.
     */
    private void abrirConStart(String ruta) throws Exception {
        Process proceso = new ProcessBuilder("cmd", "/c", "start", "", ruta)
                .redirectErrorStream(true)
                .start();
        if (!proceso.waitFor(5, TimeUnit.SECONDS)) {
            proceso.destroyForcibly();
            throw new IOException("cmd /c start no terminó en 5 segundos");
        }
        if (proceso.exitValue() != 0) {
            String salida = new String(proceso.getInputStream().readAllBytes());
            throw new IOException("cmd /c start terminó con código " + proceso.exitValue()
                    + ": " + salida.trim());
        }
    }

    private void abrirConDesktop(Path archivo) throws IOException {
        if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException("Desktop.OPEN no está disponible en este equipo");
        }
        Desktop.getDesktop().open(archivo.toFile());
    }

    /** Mejor esfuerzo: si tampoco hay portapapeles, el mensaje igual lleva la ruta. */
    private void copiarRutaAlPortapapeles(String ruta) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(ruta), null);
        } catch (Exception e) {
            log.warn("No se pudo copiar la ruta al portapapeles: {}", e.toString());
        }
    }
}
