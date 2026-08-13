package com.alejandriamakeup.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FrontendRootTest {

    @LocalServerPort
    private int puerto;

    private RestTestClient cliente;

    @BeforeEach
    void configurarCliente() {
        cliente = RestTestClient.bindToServer().baseUrl("http://localhost:" + puerto).build();
    }

    @Test
    void laRaizSirveLaPaginaDeReact() {
        cliente.get().uri("/")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(cuerpo -> org.assertj.core.api.Assertions.assertThat(cuerpo).contains("id=\"root\""));
    }
}
