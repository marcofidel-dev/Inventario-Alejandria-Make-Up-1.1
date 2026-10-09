package com.alejandriamakeup.pos.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.w3c.dom.Element;

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
                .value(cuerpo -> assertThat(cuerpo).contains("UP"));
    }

    /**
     * Sirve para saber que lo que responde en el puerto es esta app y no otro
     * programa, así que el nombre y la versión tienen que venir de verdad —
     * nunca un literal que se desincroniza de lo que dice {@code pom.xml}.
     */
    @Test
    void healthRespondeNombreYVersion() {
        String version = versionDeclaradaEnElPom();

        cliente.get().uri("/api/v1/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(cuerpo -> {
                    assertThat(cuerpo).contains("\"nombre\":\"AlejandriaMakeUp\"");
                    assertThat(cuerpo).contains("\"version\":\"" + version + "\"");
                });
    }

    /** Lee <version> directo del pom.xml, sin dependencia de maven-model, para no afirmar un literal que se desincroniza. */
    private static String versionDeclaradaEnElPom() {
        try {
            var documento = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(Path.of("pom.xml").toFile());
            var hijos = documento.getDocumentElement().getChildNodes();
            for (int i = 0; i < hijos.getLength(); i++) {
                if (hijos.item(i) instanceof Element elemento && "version".equals(elemento.getTagName())) {
                    return elemento.getTextContent().trim();
                }
            }
            throw new IllegalStateException("pom.xml no tiene un <version> de primer nivel");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
