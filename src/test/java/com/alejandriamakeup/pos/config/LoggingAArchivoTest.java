package com.alejandriamakeup.pos.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Configuration;

/**
 * {@code logback-spring.xml} manda a archivo solo cuando NINGÚN perfil activo es
 * {@code dev} ni {@code test} — justo lo que la suite normal no puede probar
 * nunca, porque todo lo demás corre con {@code @ActiveProfiles("test")} a
 * propósito, para no escribir en disco de más.
 *
 * <p>Por eso este test levanta el contexto mínimo en una JVM aparte
 * ({@link ProcesoDeLogAislado}, ver su javadoc para el porqué de esa
 * separación), sin ningún perfil activo, acorralando a
 * {@link AppPathsEnvironmentPostProcessor} dentro de un directorio temporal al
 * sustituir {@code user.home} por la duración del proceso hijo — así se
 * ejercita la resolución de rutas real (la misma que usa producción) sin
 * tocar la carpeta de datos real de quien corre la suite.
 */
class LoggingAArchivoTest {

    @Configuration
    static class ContextoVacio {
    }

    @Test
    void elArchivoDeLogSeCreaYRecibeLineas(@TempDir Path hogarFalso) throws IOException, InterruptedException {
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder constructor = new ProcessBuilder(
                javaBin, "-cp", System.getProperty("java.class.path"),
                ProcesoDeLogAislado.class.getName(), hogarFalso.toString());
        constructor.redirectErrorStream(true);

        Process proceso = constructor.start();
        String salida = new String(proceso.getInputStream().readAllBytes());
        int codigo = proceso.waitFor();
        assertThat(codigo).as("el proceso hijo no terminó bien; salida:%n%s", salida).isZero();

        Path archivo = buscarPosLog(hogarFalso);
        assertThat(archivo).exists();
        assertThat(Files.readString(archivo))
                .contains("VERIFICACION linea de prueba para el archivo de log");
    }

    private static Path buscarPosLog(Path raiz) throws IOException {
        try (Stream<Path> hallados = Files.walk(raiz)) {
            return hallados.filter(p -> p.getFileName().toString().equals("pos.log"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No se encontró pos.log bajo " + raiz));
        }
    }
}
