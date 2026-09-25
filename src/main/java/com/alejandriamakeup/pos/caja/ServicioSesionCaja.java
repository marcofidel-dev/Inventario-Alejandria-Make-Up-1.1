package com.alejandriamakeup.pos.caja;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.alejandriamakeup.pos.backup.BackupService;
import com.alejandriamakeup.pos.caja.dto.ArqueoDto;
import com.alejandriamakeup.pos.caja.dto.CerrarSesionPeticion;
import com.alejandriamakeup.pos.caja.dto.MovimientoCajaDto;
import com.alejandriamakeup.pos.caja.dto.NotaSesionCajaDto;
import com.alejandriamakeup.pos.caja.dto.SesionDto;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.consecutivos.ServicioConsecutivo;
import com.alejandriamakeup.pos.consecutivos.TipoConsecutivo;
import com.alejandriamakeup.pos.seguridad.Permiso;
import com.alejandriamakeup.pos.seguridad.PermisosPorRol;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;
import com.alejandriamakeup.pos.ventas.VentaRepository;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * Apertura, consulta y cierre de sesiones de caja.
 *
 * <p><strong>El cierre es a ciegas.</strong> Ningún método de aquí devuelve el
 * efectivo esperado de una sesión abierta, y {@link SesionDto.Abierta} no tiene
 * campo donde ponerlo. El único punto del sistema donde aparecen esperado, contado
 * y diferencia es la respuesta de {@link #cerrar}, que recibe el conteo físico en
 * la misma llamada. Invertir ese orden vaciaría de sentido el arqueo: quien cuenta
 * sabiendo el resultado esperado, cuenta hasta que le cuadre.
 */
@Service
@Transactional(readOnly = true)
public class ServicioSesionCaja {

    private static final Logger log = LoggerFactory.getLogger(ServicioSesionCaja.class);

    private final SesionCajaRepository sesionRepository;
    private final MovimientoCajaRepository movimientoRepository;
    private final ConteoDenominacionRepository conteoRepository;
    private final NotaSesionCajaRepository notaRepository;
    private final UsuarioRepository usuarioRepository;
    private final VentaRepository ventaRepository;
    private final ServicioConsecutivo servicioConsecutivo;
    private final BackupService backupService;

    public ServicioSesionCaja(SesionCajaRepository sesionRepository,
                              MovimientoCajaRepository movimientoRepository,
                              ConteoDenominacionRepository conteoRepository,
                              NotaSesionCajaRepository notaRepository,
                              UsuarioRepository usuarioRepository,
                              VentaRepository ventaRepository,
                              ServicioConsecutivo servicioConsecutivo,
                              BackupService backupService) {
        this.sesionRepository = sesionRepository;
        this.movimientoRepository = movimientoRepository;
        this.conteoRepository = conteoRepository;
        this.notaRepository = notaRepository;
        this.usuarioRepository = usuarioRepository;
        this.ventaRepository = ventaRepository;
        this.servicioConsecutivo = servicioConsecutivo;
        this.backupService = backupService;
    }

    // ------------------------------------------------------------------ abrir

    /**
     * Abre una sesión. Falla si ya hay una abierta, y el mensaje distingue el caso
     * de la sesión olvidada de un día anterior: ahí lo que hay que hacer no es
     * insistir, es cerrar la de ayer.
     *
     * <p>No recibe ningún monto: no hay base inicial, la sesión nace con el cajón en
     * cero para el sistema. Si de una noche a otra quedó efectivo físico, quien abre lo
     * declara con un movimiento INGRESO manual — ver "Reemplazo de la base inicial" en
     * la especificación.
     */
    @Transactional
    public SesionDto abrir(long usuarioId) {
        sesionRepository.buscarAbierta().ifPresent(abierta -> {
            throw esDeUnDiaAnterior(abierta)
                    ? ErrorDeAplicacion.conflicto("SESION_ABIERTA_DE_DIA_ANTERIOR",
                            "Quedó abierta la sesión " + abierta.getConsecutivo() + " del "
                                    + abierta.getFechaApertura().toLocalDate()
                                    + ". Hay que cerrarla antes de abrir una nueva.")
                    : ErrorDeAplicacion.conflicto("SESION_YA_ABIERTA",
                            "Ya hay una sesión de caja abierta: " + abierta.getConsecutivo() + ".");
        });

        Usuario usuario = usuario(usuarioId);

        SesionCaja sesion = new SesionCaja();
        sesion.setConsecutivo(servicioConsecutivo.siguiente(TipoConsecutivo.SESION_CAJA));
        sesion.setUsuarioApertura(usuario);
        sesion.setFechaApertura(Fechas.ahora());
        sesion.setEstado(EstadoSesionCaja.ABIERTA);

        SesionCaja guardada = sesionRepository.save(sesion);
        log.info("Sesión de caja {} abierta por {}", guardada.getConsecutivo(), usuario.getNombre());
        return aDto(guardada);
    }

    // --------------------------------------------------------------- consultar

    /**
     * La sesión sobre la que se puede operar <strong>hoy</strong>.
     *
     * <p>Este es el punto de entrada obligatorio para todo lo que cuelga de una
     * sesión: los movimientos manuales de hoy y, en la Fase 3, las ventas. No basta
     * con {@code buscarAbierta()}.
     *
     * <p>El motivo es un agujero fácil de pasar por alto: el bloqueo de "hay una
     * sesión de ayer sin cerrar" estaba solo en la apertura, y para vender nadie
     * necesita abrir — el punto de venta simplemente busca la sesión abierta y la
     * encuentra. Con la caja de ayer todavía abierta, las ventas y los gastos de hoy
     * entran en ella, y el arqueo del lunes acaba con la plata del martes adentro. El
     * descuadre además no se ve: la sesión cuadra consigo misma, solo que abarca dos
     * días.
     *
     * <p>Cerrar la sesión vieja sigue permitido, que es la salida: el que se bloquea
     * es operar, no cerrar.
     *
     * @throws ErrorDeAplicacion 409 si no hay sesión abierta, o si la que hay es de un
     *         día anterior
     */
    public SesionCaja sesionOperableHoy() {
        SesionCaja abierta = sesionRepository.buscarAbierta().orElseThrow(() ->
                ErrorDeAplicacion.conflicto("SIN_SESION_ABIERTA",
                        "No hay una sesión de caja abierta."));

        if (esDeUnDiaAnterior(abierta)) {
            throw ErrorDeAplicacion.conflicto("SESION_ABIERTA_DE_DIA_ANTERIOR",
                    "La sesión " + abierta.getConsecutivo() + " quedó abierta del "
                            + abierta.getFechaApertura().toLocalDate()
                            + ". Hay que cerrarla antes de seguir operando: si no, lo de hoy "
                            + "entra en el arqueo de ese día.");
        }
        return abierta;
    }

    /** Si hay una sesión abierta y viene de un día anterior. Para avisar en pantalla. */
    public boolean hayUnaSesionOlvidadaDeUnDiaAnterior() {
        return sesionRepository.buscarAbierta().filter(this::esDeUnDiaAnterior).isPresent();
    }

    /** La sesión abierta, si hay. Sin esperado, sin totales. */
    public SesionDto actual() {
        return aDto(sesionRepository.buscarAbierta().orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No hay ninguna sesión de caja abierta.")));
    }

    public SesionDto porId(long sesionId, long usuarioId, Rol rol) {
        return aDto(buscarConPermiso(sesionId, usuarioId, rol));
    }

    /**
     * El historial. La EMPLEADA solo ve las suyas; la sesión abierta la ve
     * cualquiera con permiso de operar caja, porque necesita saber que hay una y su
     * DTO no revela nada sensible.
     */
    public List<SesionDto> listar(long usuarioId, Rol rol) {
        List<SesionCaja> sesiones = PermisosPorRol.puede(rol, Permiso.VER_SESIONES_DE_OTROS)
                ? sesionRepository.findAllByOrderByFechaAperturaDesc()
                : sesionRepository.findByUsuarioAperturaIdOrderByFechaAperturaDesc(usuarioId);

        // Una sola consulta para todo el listado, no una por fila. Lo mismo con las
        // notas: al año son unas trescientas sesiones y el listado se pinta entero.
        Map<Long, List<NotaSesionCajaDto>> notas = notasDe(sesiones.stream().map(SesionCaja::getId).toList());

        return sesiones.stream().map(sesion -> aDto(sesion, notas)).toList();
    }

    /**
     * Los movimientos de una sesión, ya como DTO.
     *
     * <p>El mapeo va aquí y no en el controlador a propósito: {@code MovimientoCajaDto}
     * lee {@code usuario.nombre}, que es LAZY, y con {@code open-in-view: false} fuera
     * de la transacción eso revienta con {@code LazyInitializationException}.
     */
    public List<MovimientoCajaDto> movimientosDe(long sesionId, long usuarioId, Rol rol) {
        SesionCaja sesion = buscarConPermiso(sesionId, usuarioId, rol);
        return movimientoRepository.findBySesionIdOrderByFechaAsc(sesion.getId())
                .stream()
                .map(MovimientoCajaDto::de)
                .toList();
    }

    // ----------------------------------------------------------------- notas

    /**
     * Agrega una nota a una sesión. Nunca modifica la sesión.
     *
     * <p>Se puede anotar sobre una sesión ya cerrada, y días después: es lo que hace
     * que una diferencia se pueda explicar <em>cuando se sabe que la hubo</em>, que es
     * el único momento en que hay algo que explicar. La fila de {@code sesion_caja}
     * no se toca — sus montos, fechas y usuarios siguen siendo inmutables.
     *
     * <p>Quién puede anotar sobre qué sesión lo decide {@link #buscarConPermiso}, el
     * mismo que decide quién puede verla: no tendría sentido poder anotar sobre una
     * sesión que no se puede leer.
     */
    @Transactional
    public NotaSesionCajaDto anotar(long sesionId, String texto, long usuarioId, Rol rol) {
        SesionCaja sesion = buscarConPermiso(sesionId, usuarioId, rol);

        NotaSesionCaja nota = notaRepository.save(NotaSesionCaja.builder()
                .sesion(sesion)
                .usuario(usuario(usuarioId))
                .fecha(Fechas.ahora())
                .texto(texto.trim())
                .build());

        log.info("Nota agregada a la sesión {}", sesion.getConsecutivo());
        return NotaSesionCajaDto.de(nota);
    }

    public List<NotaSesionCajaDto> notasDe(long sesionId, long usuarioId, Rol rol) {
        SesionCaja sesion = buscarConPermiso(sesionId, usuarioId, rol);
        return notaRepository.findBySesionIdOrderByFechaAsc(sesion.getId())
                .stream()
                .map(NotaSesionCajaDto::de)
                .toList();
    }

    // ----------------------------------------------------------------- cerrar

    /**
     * Cierra la sesión con el conteo físico y devuelve — por primera y única vez —
     * esperado, contado y diferencia.
     *
     * <p>{@code fechaCierre} es siempre <em>ahora</em>, nunca una fecha enviada por
     * el cliente, ni cuando se cierra la sesión olvidada de ayer. Así el registro
     * dice cuándo se cerró de verdad y nadie puede antedatar una caja.
     *
     * <p>El respaldo se dispara <strong>después del commit</strong>. No es estilo:
     * {@code respaldarSiEsNecesario()} pide su propia conexión, y con el pool de una
     * sola conexión llamarlo dentro de la transacción se cuelga hasta el timeout.
     */
    @Transactional
    public ArqueoDto cerrar(long sesionId, CerrarSesionPeticion peticion, long usuarioId) {
        SesionCaja sesion = sesionRepository.findById(sesionId).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe la sesión de caja " + sesionId));

        if (sesion.getEstado() != EstadoSesionCaja.ABIERTA) {
            throw ErrorDeAplicacion.conflicto("SESION_CERRADA",
                    "La sesión " + sesion.getConsecutivo() + " ya está cerrada y es inmutable.");
        }

        long contado = totalDelConteo(peticion.conteo());
        // Solo lo que entró y salió durante la sesión: no hay sumando de base.
        long esperado = movimientoRepository.sumaDe(sesion.getId());

        guardarConteo(sesion, peticion.conteo());

        sesion.setEfectivoContado(contado);
        sesion.setEfectivoEsperado(esperado);
        sesion.setDiferencia(contado - esperado);
        sesion.setMontoRetirado(peticion.montoRetirado());
        sesion.setUsuarioCierre(usuario(usuarioId));
        sesion.setFechaCierre(Fechas.ahora());
        sesion.setEstado(EstadoSesionCaja.CERRADA);

        if (peticion.observaciones() != null && !peticion.observaciones().isBlank()) {
            sesion.setObservaciones(peticion.observaciones());
        }

        SesionCaja cerrada = sesionRepository.save(sesion);
        log.info("Sesión {} cerrada: esperado {}, contado {}, diferencia {}",
                cerrada.getConsecutivo(), esperado, contado, cerrada.getDiferencia());

        respaldarDespuesDelCommit();

        return new ArqueoDto((SesionDto.Cerrada) aDto(cerrada),
                ventaRepository.desglosePorMetodo(cerrada.getId()));
    }

    private void respaldarDespuesDelCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                backupService.respaldarSiEsNecesario();
            }
        });
    }

    private long totalDelConteo(List<CerrarSesionPeticion.Denominacion> conteo) {
        Set<Long> vistas = new HashSet<>();
        long total = 0;
        for (CerrarSesionPeticion.Denominacion linea : conteo) {
            if (!vistas.add(linea.denominacion())) {
                throw ErrorDeAplicacion.peticionInvalida(
                        "La denominación " + linea.denominacion() + " viene repetida en el conteo.");
            }
            total += linea.denominacion() * linea.cantidad();
        }
        return total;
    }

    private void guardarConteo(SesionCaja sesion, List<CerrarSesionPeticion.Denominacion> conteo) {
        for (CerrarSesionPeticion.Denominacion linea : conteo) {
            ConteoDenominacion fila = new ConteoDenominacion();
            fila.setSesion(sesion);
            fila.setDenominacion(linea.denominacion());
            fila.setCantidad(linea.cantidad());
            conteoRepository.save(fila);
        }
    }

    // ----------------------------------------------------------------- apoyo

    /**
     * Una sesión CERRADA solo la ve quien la abrió, salvo que tenga el permiso de
     * ver las de otros. La ABIERTA la ve cualquiera: es la sesión en curso de la
     * tienda y su DTO no expone nada que el cierre a ciegas deba proteger.
     */
    private SesionCaja buscarConPermiso(long sesionId, long usuarioId, Rol rol) {
        SesionCaja sesion = sesionRepository.findById(sesionId).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe la sesión de caja " + sesionId));

        boolean esSuya = sesion.getUsuarioApertura().getId() == usuarioId;
        boolean estaAbierta = sesion.getEstado() == EstadoSesionCaja.ABIERTA;

        if (!esSuya && !estaAbierta && !PermisosPorRol.puede(rol, Permiso.VER_SESIONES_DE_OTROS)) {
            throw ErrorDeAplicacion.sinPermiso(
                    "La sesión " + sesion.getConsecutivo() + " la abrió otra persona.");
        }
        return sesion;
    }

    private boolean esDeUnDiaAnterior(SesionCaja sesion) {
        return sesion.getFechaApertura().toLocalDate().isBefore(LocalDate.now());
    }

    private Usuario usuario(long usuarioId) {
        return usuarioRepository.findById(usuarioId).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe el usuario " + usuarioId));
    }

    private SesionDto aDto(SesionCaja sesion) {
        return aDto(sesion, notasDe(List.of(sesion.getId())));
    }

    /** Las notas de varias sesiones, agrupadas por sesión. Una consulta, no una por fila. */
    private Map<Long, List<NotaSesionCajaDto>> notasDe(List<Long> sesionIds) {
        if (sesionIds.isEmpty()) return Map.of();

        return notaRepository.findBySesionIdInOrderByFechaAsc(sesionIds).stream()
                .collect(Collectors.groupingBy(
                        nota -> nota.getSesion().getId(),
                        Collectors.mapping(NotaSesionCajaDto::de, Collectors.toList())));
    }

    private SesionDto aDto(SesionCaja sesion, Map<Long, List<NotaSesionCajaDto>> notas) {
        if (sesion.getEstado() == EstadoSesionCaja.ABIERTA) {
            return new SesionDto.Abierta(
                    sesion.getId(),
                    sesion.getConsecutivo(),
                    sesion.getEstado().name(),
                    String.valueOf(sesion.getFechaApertura()),
                    sesion.getUsuarioApertura().getNombre(),
                    movimientoRepository.findBySesionIdOrderByFechaAsc(sesion.getId()).size(),
                    esDeUnDiaAnterior(sesion));
        }

        return new SesionDto.Cerrada(
                sesion.getId(),
                sesion.getConsecutivo(),
                sesion.getEstado().name(),
                String.valueOf(sesion.getFechaApertura()),
                String.valueOf(sesion.getFechaCierre()),
                sesion.getUsuarioApertura().getNombre(),
                sesion.getUsuarioCierre() == null ? null : sesion.getUsuarioCierre().getNombre(),
                sesion.getEfectivoEsperado(),
                sesion.getEfectivoContado(),
                sesion.getDiferencia(),
                sesion.getMontoRetirado(),
                sesion.getObservaciones(),
                notas.getOrDefault(sesion.getId(), List.of()));
    }
}
