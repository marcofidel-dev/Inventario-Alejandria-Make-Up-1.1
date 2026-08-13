package com.alejandriamakeup.pos.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.alejandriamakeup.pos.PosApplication;

@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HealthControllerTest {

    @LocalServerPort
    private int puerto;

    private RestTestClient cliente;

    @BeforeEach
    void configurarCliente() {
        cliente = RestTestClient.bindToServer().baseUrl("http://localhost:" + puerto).build();
    }

    @Test
    void healthRespondeUp() {
        cliente.get().uri("/api/v1/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(cuerpo -> org.assertj.core.api.Assertions.assertThat(cuerpo).contains("UP"));
    }
}
