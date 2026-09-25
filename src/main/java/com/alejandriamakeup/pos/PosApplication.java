package com.alejandriamakeup.pos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PosApplication {

    /**
     * <strong>Sin modo headless.</strong> Esto es una aplicación de escritorio, no un
     * servidor: abre el recibo con el visor de PDF del sistema, y para eso
     * {@code java.awt.Desktop} tiene que estar disponible.
     *
     * <p>Se pone <strong>aquí y no en {@code application.yml}</strong>, y no es
     * cuestión de gusto: {@code SpringApplication.run} fija {@code java.awt.headless}
     * antes de leer la configuración, así que un {@code spring.main.headless: false}
     * en el YAML llega tarde y no hace nada. El síntoma es de los que cuestan una
     * tarde: {@code Desktop.isDesktopSupported()} devuelve false y el programa
     * responde "este equipo no tiene con qué abrir el PDF" en un equipo que sí tiene
     * con qué.
     *
     * <p>En una máquina sin sesión gráfica esto no rompe nada:
     * {@code GraphicsEnvironment.isHeadless()} sigue diciendo la verdad por su cuenta
     * y {@code AbridorDelSistema} responde que no hay visor, que es lo correcto.
     */
    public static void main(String[] args) {
        SpringApplication aplicacion = new SpringApplication(PosApplication.class);
        aplicacion.setHeadless(false);
        aplicacion.run(args);
    }
}
