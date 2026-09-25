package com.alejandriamakeup.pos.caja;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.caja.dto.ArqueoDto;
import com.alejandriamakeup.pos.caja.dto.CerrarSesionPeticion;
import com.alejandriamakeup.pos.caja.dto.CerrarSesionPeticion.Denominacion;
import com.alejandriamakeup.pos.caja.dto.SesionDto;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;
import com.alejandriamakeup.pos.ventas.EstadoVenta;
import com.alejandriamakeup.pos.ventas.MetodoPago;
import com.alejandriamakeup.pos.ventas.Venta;
import com.alejandriamakeup.pos.ventas.VentaRepository;

/**
 * El efectivo esperado es {@code SUM(movimiento_caja.monto)} de la sesión, y nada más.
 *
 * <p>La base inicial dejó de existir: el arqueo es exclusivamente el dinero que entró y
 * salió durante la sesión. Estos dos casos fijan las dos caras de esa regla:
 * <ul>
 *   <li>abrir, vender en efectivo y cerrar da un esperado igual al total de la venta,
 *       sin ningún otro sumando;</li>
 *   <li>el efectivo que quedó de una noche a otra se declara con un INGRESO manual al
 *       abrir, y ese ingreso —y solo él— entra en el esperado.</li>
 * </ul>
 *
 * <p>Se prueba contra los servicios y no por HTTP: lo que interesa es el número que
 * sale de {@code cerrar()}, y armar catálogo e inventario para vender de verdad no
 * agregaría nada a esa afirmación. Cada caso abre su propia sesión y la cierra, así que
 * son independientes entre sí. Los montos no se repiten entre casos ni con ningún id.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
class EfectivoEsperadoSinBaseTest {

    private static final String URL = BaseDatosAislada.urlNueva("esperado-sin-base");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @Autowired
    private ServicioSesionCaja servicioSesion;

    @Autowired
    private ServicioMovimientoCaja servicioMovimiento;

    @Autowired
    private SesionCajaRepository sesionRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private VentaRepository ventaRepository;

    private Usuario usuario;

    @BeforeEach
    void prepararUsuaria() {
        usuario = usuarioRepository.findByNombre("Cajera").orElseGet(() -> {
            Usuario nueva = new Usuario();
            nueva.setNombre("Cajera");
            nueva.setRol(Rol.EMPLEADA);
            nueva.setPinHash("$2a$10$hashDePrueba");
            nueva.setActivo(true);
            nueva.setFechaCreacion(Fechas.ahora());
            return usuarioRepository.save(nueva);
        });
    }

    @Test
    void abrirVenderEnEfectivoYCerrarDaExactamenteElTotalDeLaVenta() {
        long id = abrir();

        servicioMovimiento.registrarVenta(guardarVenta(id, MetodoPago.EFECTIVO, 87_000));
        // Una venta digital entra en la sesión pero no toca el cajón: tampoco suma.
        servicioMovimiento.registrarVenta(guardarVenta(id, MetodoPago.NEQUI, 310_000));

        SesionDto.Cerrada cerrada = cerrar(id, 87_000);

        System.out.println("VERIFICACION venta en efectivo de 87000 => esperado "
                + cerrada.efectivoEsperado() + ", diferencia " + cerrada.diferencia());
        assertThat(cerrada.efectivoEsperado()).isEqualTo(87_000L);
        assertThat(cerrada.diferencia()).isZero();
        assertThat(sesionRepository.findById(id).orElseThrow().getBaseInicial()).isZero();
    }

    @Test
    void elEfectivoDejadoDeAyerDeclaradoComoIngresoSeSumaAlEsperado() {
        long id = abrir();

        // Lo que quedó en el cajón de anoche: una decisión consciente de quien abre,
        // que queda en el historial como cualquier otro ingreso.
        servicioMovimiento.registrarManual(TipoMovimientoCaja.INGRESO, 63_000,
                "efectivo dejado de sesión anterior", usuario.getId());
        servicioMovimiento.registrarVenta(guardarVenta(id, MetodoPago.EFECTIVO, 41_500));

        SesionDto.Cerrada cerrada = cerrar(id, 104_500);

        System.out.println("VERIFICACION ingreso de apertura 63000 + venta 41500 => esperado "
                + cerrada.efectivoEsperado() + ", diferencia " + cerrada.diferencia());
        assertThat(cerrada.efectivoEsperado()).isEqualTo(63_000L + 41_500L);
        assertThat(cerrada.diferencia()).isZero();
    }

    // ------------------------------------------------------------------- apoyo

    private long abrir() {
        SesionDto abierta = servicioSesion.abrir(usuario.getId());
        return ((SesionDto.Abierta) abierta).id();
    }

    /** Cierra contando monedas de 500: todos los montos del test son múltiplos de 500. */
    private SesionDto.Cerrada cerrar(long id, long contado) {
        ArqueoDto arqueo = servicioSesion.cerrar(id,
                new CerrarSesionPeticion(List.of(new Denominacion(500L, (int) (contado / 500))),
                        0L, null),
                usuario.getId());
        return arqueo.sesion();
    }

    private Venta guardarVenta(long sesionId, MetodoPago metodo, long total) {
        Venta venta = new Venta();
        venta.setUuid(UUID.randomUUID().toString());
        venta.setConsecutivo("V-" + UUID.randomUUID());
        venta.setSesionCaja(sesionRepository.findById(sesionId).orElseThrow());
        venta.setUsuario(usuario);
        venta.setFecha(Fechas.ahora());
        venta.setSubtotal(total);
        venta.setDescuento(0);
        venta.setTotal(total);
        venta.setMetodoPago(metodo);
        venta.setEstado(EstadoVenta.COMPLETADA);
        return ventaRepository.save(venta);
    }
}
