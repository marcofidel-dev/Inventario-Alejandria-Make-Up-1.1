package com.alejandriamakeup.pos.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Arranca un contexto mínimo en su propia JVM, para que {@link LoggingAArchivoTest}
 * pueda comprobar el apagado a archivo de {@code logback-spring.xml} sin heredar el
 * {@code LoggingSystem} ya inicializado por los cientos de {@code @SpringBootTest}
 * que corrieron antes en el mismo proceso de Surefire.
 *
 * <p>Spring Boot no reconfigura el logging de forma confiable entre dos
 * {@code SpringApplication.run()} distintos dentro de la misma JVM: una vez que el
 * primer test deja el {@code LoggingSystem} inicializado con el perfil
 * {@code test} (solo consola), un segundo {@code run()} sin perfil —el que
 * necesita este test, para ejercitar la rama de archivo— se queda pegado a esa
 * configuración vieja en vez de releer {@code logback-spring.xml}. Confirmado
 * viendo la línea de log de la prueba salir con el formato de consola aunque
 * ningún perfil estuviera activo. Por eso el único intento de proceso en el
 * mismo JVM que este test, el primero, es también el único: process aislado.
 */
public final class ProcesoDeLogAislado {

    private ProcesoDeLogAislado() {
    }

    public static void main(String[] args) throws Exception {
        String hogarFalso = args[0];
        System.setProperty("user.home", hogarFalso);

        SpringApplication app = new SpringApplication(LoggingAArchivoTest.ContextoVacio.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext contexto = app.run("--spring.main.banner-mode=off");

        Logger log = LoggerFactory.getLogger(ProcesoDeLogAislado.class);
        log.info("VERIFICACION linea de prueba para el archivo de log");

        contexto.close();
    }
}
