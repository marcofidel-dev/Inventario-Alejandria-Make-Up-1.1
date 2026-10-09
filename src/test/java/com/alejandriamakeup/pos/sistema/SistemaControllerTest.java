package com.alejandriamakeup.pos.sistema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import com.alejandriamakeup.pos.backup.BackupService;
import com.alejandriamakeup.pos.backup.BackupService.ResultadoRespaldo;
import com.alejandriamakeup.pos.sistema.SistemaController.ApagadoPeticion;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * Unitario, sin Spring: es la única forma real de probar el 403 por IP no
 * local. Un {@code @SpringBootTest} con cliente HTTP real pega contra
 * {@code localhost}, que siempre ES loopback — no hay forma de falsear la IP
 * de origen pegándole al servidor desde el mismo host.
 */
class SistemaControllerTest {

    private final BackupService backupService = mock(BackupService.class);
    private final ApagadorDeAplicacion apagador = mock(ApagadorDeAplicacion.class);
    private final SistemaController controller = new SistemaController(backupService, apagador);

    @Test
    void unaIpNoLocalDaCuarentaYTresAunqueMientaElEncabezado() {
        MockHttpServletRequest solicitud = new MockHttpServletRequest();
        solicitud.setRemoteAddr("203.0.113.5");
        // El encabezado es mentira a propósito: esLocal() nunca debe leerlo.
        solicitud.addHeader("X-Forwarded-For", "127.0.0.1");
        solicitud.addHeader("Forwarded", "for=127.0.0.1");

        assertThatThrownBy(() -> controller.apagar(null, solicitud))
                .isInstanceOf(ErrorDeAplicacion.class)
                .satisfies(fallo -> {
                    ErrorDeAplicacion error = (ErrorDeAplicacion) fallo;
                    assertThat(error.getEstado()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error.getCodigo()).isEqualTo("ACCESO_SOLO_LOCAL");
                });

        verifyNoInteractions(backupService, apagador);
    }

    @Test
    void loopbackIpv4PasaElChequeo() {
        MockHttpServletRequest solicitud = new MockHttpServletRequest();
        solicitud.setRemoteAddr("127.0.0.1");
        when(backupService.respaldarConTimeout()).thenReturn(ResultadoRespaldo.exito());

        var respuesta = controller.apagar(null, solicitud);

        assertThat(respuesta.getBody().exitoso()).isTrue();
        verify(apagador).programarApagado();
    }

    @Test
    void loopbackIpv6PasaElChequeo() {
        MockHttpServletRequest solicitud = new MockHttpServletRequest();
        solicitud.setRemoteAddr("::1");
        when(backupService.respaldarConTimeout()).thenReturn(ResultadoRespaldo.exito());

        var respuesta = controller.apagar(null, solicitud);

        assertThat(respuesta.getBody().exitoso()).isTrue();
    }

    @Test
    void siElRespaldoFallaNoApagaYDiceElMotivo() {
        MockHttpServletRequest solicitud = new MockHttpServletRequest();
        solicitud.setRemoteAddr("127.0.0.1");
        when(backupService.respaldarConTimeout()).thenReturn(ResultadoRespaldo.fallo("sin disco"));

        var respuesta = controller.apagar(null, solicitud);

        assertThat(respuesta.getBody().exitoso()).isFalse();
        assertThat(respuesta.getBody().motivo()).isEqualTo("sin disco");
        verifyNoInteractions(apagador);
    }

    @Test
    void forzarApagaSinReintentarElRespaldo() {
        MockHttpServletRequest solicitud = new MockHttpServletRequest();
        solicitud.setRemoteAddr("127.0.0.1");

        var respuesta = controller.apagar(new ApagadoPeticion(true), solicitud);

        assertThat(respuesta.getBody().exitoso()).isTrue();
        verifyNoInteractions(backupService);
        verify(apagador).programarApagado();
    }
}
