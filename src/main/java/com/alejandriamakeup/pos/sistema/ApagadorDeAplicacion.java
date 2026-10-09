package com.alejandriamakeup.pos.sistema;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * El único sitio que de verdad apaga la JVM a pedido del usuario. Aparte de
 * {@link SistemaController} para que su test pueda sustituirlo por un doble y
 * no autoapagar la suite.
 */
@Component
public class ApagadorDeAplicacion {

    private static final Logger log = LoggerFactory.getLogger(ApagadorDeAplicacion.class);

    /** Tiempo para que la respuesta HTTP ya salga por el socket antes de cerrar el contexto. */
    private static final Duration RETRASO = Duration.ofMillis(400);

    private final ConfigurableApplicationContext contexto;

    public ApagadorDeAplicacion(ConfigurableApplicationContext contexto) {
        this.contexto = contexto;
    }

    /**
     * Cerrar el contexto en el mismo hilo de la petición le cortaría la
     * respuesta al cliente a medio enviar: por eso el apagado real ocurre en
     * un hilo aparte, con un retraso corto.
     */
    public void programarApagado() {
        Thread hilo = new Thread(() -> {
            try {
                Thread.sleep(RETRASO.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("Apagando la aplicación por pedido desde POST /api/v1/sistema/apagado");
            int codigo = SpringApplication.exit(contexto);
            System.exit(codigo);
        }, "apagado-por-peticion");
        hilo.start();
    }
}
