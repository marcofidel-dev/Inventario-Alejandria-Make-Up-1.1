package com.alejandriamakeup.pos.caja;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.alejandriamakeup.pos.PosApplication;
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
 * Solo el efectivo toca el cajón.
 *
 * <p>Una venta por Nequi, Daviplata, tarjeta o transferencia entra en la sesión pero
 * <strong>no</strong> genera movimiento de caja: se concilia aparte contra el
 * extracto. Si lo generara, el arqueo del día pediría plata física que nunca entró y
 * toda sesión cerraría con faltante — un faltante fantasma que además crecería con
 * cada venta digital.
 *
 * <p>Se prueba contra {@code ServicioMovimientoCaja.registrarVenta}, que es la pieza
 * de caja sobre la que se apoyará el módulo de ventas. No hace falta implementar
 * ventas para verificar la regla: hace falta que la pieza que las va a sostener ya la
 * respete.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
class MovimientoDeVentaTest {

    private static final String URL = BaseDatosAislada.urlNueva("venta-en-caja");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @Autowired
    private ServicioMovimientoCaja servicioMovimiento;

    @Autowired
    private MovimientoCajaRepository movimientoRepository;

    @Autowired
    private SesionCajaRepository sesionRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private VentaRepository ventaRepository;

    private SesionCaja sesion;
    private Usuario usuario;

    @BeforeEach
    void abrirSesion() {
        usuario = usuarioRepository.findByNombre("Cajera").orElseGet(() -> {
            Usuario nueva = new Usuario();
            nueva.setNombre("Cajera");
            nueva.setRol(Rol.EMPLEADA);
            nueva.setPinHash("$2a$10$hashDePrueba");
            nueva.setActivo(true);
            nueva.setFechaCreacion(Fechas.ahora());
            return usuarioRepository.save(nueva);
        });

        sesion = sesionRepository.buscarAbierta().orElseGet(() -> {
            SesionCaja nueva = new SesionCaja();
            nueva.setConsecutivo("S-" + UUID.randomUUID());
            nueva.setUsuarioApertura(usuario);
            nueva.setFechaApertura(Fechas.ahora());
            nueva.setBaseInicial(100_000);
            nueva.setEstado(EstadoSesionCaja.ABIERTA);
            return sesionRepository.save(nueva);
        });
    }

    @Test
    void unaVentaEnEfectivoSiGeneraMovimiento() {
        Venta venta = guardarVenta(MetodoPago.EFECTIVO, 45_000);

        MovimientoCaja movimiento = servicioMovimiento.registrarVenta(venta);

        System.out.println("VERIFICACION venta EFECTIVO de 45000 => movimiento "
                + (movimiento == null ? "null" : movimiento.getTipo() + " " + movimiento.getMonto()));
        assertThat(movimiento).isNotNull();
        assertThat(movimiento.getTipo()).isEqualTo(TipoMovimientoCaja.VENTA_EFECTIVO);
        assertThat(movimiento.getMonto()).isEqualTo(45_000);
        assertThat(movimientoRepository.findByVentaId(venta.getId())).hasSize(1);
    }

    @Test
    void ningunOtroMetodoDePagoTocaElCajon() {
        for (MetodoPago metodo : MetodoPago.values()) {
            if (metodo == MetodoPago.EFECTIVO) {
                continue;
            }

            Venta venta = guardarVenta(metodo, 80_000);
            MovimientoCaja movimiento = servicioMovimiento.registrarVenta(venta);

            System.out.println("VERIFICACION venta " + metodo + " => movimiento "
                    + (movimiento == null ? "ninguno (correcto)" : "¡" + movimiento.getMonto() + "!"));
            assertThat(movimiento)
                    .withFailMessage("Una venta por %s no debe generar movimiento de caja", metodo)
                    .isNull();
            assertThat(movimientoRepository.findByVentaId(venta.getId())).isEmpty();
        }
    }

    /** Y el efecto que importa: el esperado no se infla con las ventas digitales. */
    @Test
    void elEfectivoEsperadoSoloCuentaLoQueEntroEnEfectivo() {
        servicioMovimiento.registrarVenta(guardarVenta(MetodoPago.EFECTIVO, 30_000));
        servicioMovimiento.registrarVenta(guardarVenta(MetodoPago.NEQUI, 500_000));
        servicioMovimiento.registrarVenta(guardarVenta(MetodoPago.TARJETA, 400_000));
        servicioMovimiento.registrarVenta(guardarVenta(MetodoPago.EFECTIVO, 20_000));

        long suma = servicioMovimiento.sumaDe(sesion.getId());
        long esperado = sesion.getBaseInicial() + suma;

        System.out.println("VERIFICACION 30000 y 20000 en efectivo, 900000 en digital => "
                + "suma de movimientos " + suma + ", esperado en cajón " + esperado);
        assertThat(suma).isEqualTo(50_000);
        assertThat(esperado).isEqualTo(150_000);
    }

    private Venta guardarVenta(MetodoPago metodo, long total) {
        Venta venta = new Venta();
        venta.setUuid(UUID.randomUUID().toString());
        venta.setConsecutivo("V-" + UUID.randomUUID());
        venta.setSesionCaja(sesion);
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
