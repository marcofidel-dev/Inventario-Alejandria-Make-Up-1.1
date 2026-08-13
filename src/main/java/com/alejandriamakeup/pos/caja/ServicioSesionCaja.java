package com.alejandriamakeup.pos.caja;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.alejandriamakeup.pos.backup.BackupService;
import com.alejandriamakeup.pos.caja.dto.CerrarSesionPeticion;
import com.alejandriamakeup.pos.caja.dto.SesionDto;
import com.alejandriamakeup.pos.caja.dto.SugerenciaAperturaDto;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.consecutivos.ServicioConsecutivo;
import com.alejandriamakeup.pos.consecutivos.TipoConsecutivo;
import com.alejandriamakeup.pos.seguridad.Permiso;
import com.alejandriamakeup.pos.seguridad.PermisosPorRol;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;
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
    private final UsuarioRepository usuarioRepository;
    private final ServicioConsecutivo servicioConsecutivo;
    private final BackupService backupService;

    public ServicioSesionCaja(SesionCajaRepository sesionRepository,
                              MovimientoCajaRepository movimientoRepository,
                              ConteoDenominacionRepository conteoRepository,
                              UsuarioRepository usuarioRepository,
                              ServicioConsecutivo servicioConsecutivo,
                              BackupService backupService) {
        this.sesionRepository = sesionRepository;
        this.movimientoRepository = movimientoRepository;
        this.conteoRepository = conteoRepository;
        this.usuarioRepository = usuarioRepository;
        this.servicioConsecutivo = servicioConsecutivo;
        this.backupService = backupService;
    }

    // ------------------------------------------------------------------ abrir

    /**
     * Abre una sesión. Falla si ya hay una abierta, y el mensaje distingue el caso
     * de la sesión olvidada de un día anterior: ahí lo que hay que hacer no es
     * insistir, es cerrar la de ayer.
     */
    @Transactional
    public SesionDto abrir(long baseInicial, String observaciones, long usuarioId) {
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
        sesion.setBaseInicial(baseInicial);
        sesion.setEstado(EstadoSesionCaja.ABIERTA);
        sesion.setObservaciones(observaciones);

        SesionCaja guardada = sesionRepository.save(sesion);
        log.info("Sesión de caja {} abierta por {}", guardada.getConsecutivo(), usuario.getNombre());
        return aDto(guardada);
    }

    /**
     * La base propuesta para abrir. Responde 409 si ya hay una sesión abierta: ver
     * {@link SugerenciaAperturaDto} para por qué eso no es un detalle.
     */
    public SugerenciaAperturaDto sugerenciaDeApertura() {
        if (sesionRepository.buscarAbierta().isPresent()) {
            throw ErrorDeAplicacion.conflicto("SESION_YA_ABIERTA",
                    "Ya hay una sesión abierta: no hay base que sugerir.");
        }

        return sesionRepository.findFirstByEstadoOrderByFechaCierreDesc(EstadoSesionCaja.CERRADA)
                .filter(ultima -> ultima.getBaseSiguiente() != null)
                .map(ultima -> new SugerenciaAperturaDto(ultima.getBaseSiguiente(),
                        "Base dejada por la sesión " + ultima.getConsecutivo()))
                .orElseGet(() -> new SugerenciaAperturaDto(0L, "No hay sesiones cerradas previas"));
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

    /** La sesión abierta, si hay. Sin base inicial, sin esperado, sin totales. */
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

        // Una sola consulta para todo el listado, no una por fila.
        boolean hayAbierta = sesionRepository.buscarAbierta().isPresent();
        return sesiones.stream().map(sesion -> aDto(sesion, hayAbierta)).toList();
    }

    public List<MovimientoCaja> movimientosDe(long sesionId, long usuarioId, Rol rol) {
        SesionCaja sesion = buscarConPermiso(sesionId, usuarioId, rol);
        return movimientoRepository.findBySesionIdOrderByFechaAsc(sesion.getId());
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
    public SesionDto.Cerrada cerrar(long sesionId, CerrarSesionPeticion peticion, long usuarioId) {
        SesionCaja sesion = sesionRepository.findById(sesionId).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe la sesión de caja " + sesionId));

        if (sesion.getEstado() != EstadoSesionCaja.ABIERTA) {
            throw ErrorDeAplicacion.conflicto("SESION_CERRADA",
                    "La sesión " + sesion.getConsecutivo() + " ya está cerrada y es inmutable.");
        }

        long contado = totalDelConteo(peticion.conteo());
        long esperado = sesion.getBaseInicial() + movimientoRepository.sumaDe(sesion.getId());

        guardarConteo(sesion, peticion.conteo());

        sesion.setEfectivoContado(contado);
        sesion.setEfectivoEsperado(esperado);
        sesion.setDiferencia(contado - esperado);
        sesion.setMontoRetirado(peticion.montoRetirado());
        sesion.setBaseSiguiente(peticion.baseSiguiente());
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

        return (SesionDto.Cerrada) aDto(cerrada);
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
        return aDto(sesion, sesionRepository.buscarAbierta().isPresent());
    }

    /**
     * Con una sesión abierta, el {@code baseSiguiente} de las sesiones cerradas se
     * omite.
     *
     * <p>Esto lo destapó el barrido de {@code CierreACiegasTest} y es la misma fuga
     * que la de la sugerencia de apertura, entrando por otra puerta: el
     * {@code baseSiguiente} de la última sesión cerrada <strong>es</strong> el
     * {@code base_inicial} de la que está en curso cuando la cajera acepta la base
     * propuesta. Publicarlo en el historial, junto con la lista de movimientos que sí
     * muestra montos, permite calcular el efectivo esperado al centavo y el arqueo a
     * ciegas deja de ser ciego.
     *
     * <p>Cerrada la caja del día, el dato vuelve a aparecer: ya no hay nada que
     * proteger.
     */
    private SesionDto aDto(SesionCaja sesion, boolean hayUnaSesionAbierta) {
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
                sesion.getBaseInicial(),
                sesion.getEfectivoEsperado(),
                sesion.getEfectivoContado(),
                sesion.getDiferencia(),
                sesion.getMontoRetirado(),
                hayUnaSesionAbierta ? null : sesion.getBaseSiguiente(),
                sesion.getObservaciones());
    }
}
