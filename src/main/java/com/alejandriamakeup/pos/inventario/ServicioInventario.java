package com.alejandriamakeup.pos.inventario;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.catalogo.ServicioVariante;
import com.alejandriamakeup.pos.catalogo.Variante;
import com.alejandriamakeup.pos.catalogo.VarianteRepository;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.inventario.dto.MovimientoInventarioDto;
import com.alejandriamakeup.pos.inventario.dto.PeticionesInventario;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * Las dos puertas por las que entra inventario que no viene de una compra: la carga
 * inicial y los ajustes.
 *
 * <p>Ambas escriben en el ledger append-only, nunca sobre un campo de stock — que no
 * existe. La carga inicial además fija el {@code costo_promedio} de la variante, y es
 * uno de los dos únicos sitios de todo el sistema autorizados a tocarlo (el otro será
 * la recepción de compras).
 */
@Service
@Transactional(readOnly = true)
public class ServicioInventario {

    private static final Logger log = LoggerFactory.getLogger(ServicioInventario.class);

    private final MovimientoInventarioRepository movimientoRepository;
    private final VarianteRepository varianteRepository;
    private final ServicioVariante servicioVariante;
    private final UsuarioRepository usuarioRepository;

    public ServicioInventario(MovimientoInventarioRepository movimientoRepository,
                              VarianteRepository varianteRepository,
                              ServicioVariante servicioVariante,
                              UsuarioRepository usuarioRepository) {
        this.movimientoRepository = movimientoRepository;
        this.varianteRepository = varianteRepository;
        this.servicioVariante = servicioVariante;
        this.usuarioRepository = usuarioRepository;
    }

    /**
     * Registra las existencias iniciales de un lote de variantes.
     *
     * <p><strong>Todo o nada.</strong> El lote entero va en una transacción: si la
     * línea cuarenta está mal, no queda inventario a medio cargar que alguien tenga
     * que reconciliar a mano sin saber por dónde se quedó. El error nombra la variante
     * que lo rompió.
     *
     * <p>Cada línea fija {@code costo_promedio = costoUnitario}. Es el valor de
     * partida: a partir de la primera compra recibida, el promedio ponderado se
     * recalcula desde ahí.
     */
    @Transactional
    public List<MovimientoInventarioDto> cargaInicial(PeticionesInventario.CargaInicial peticion,
                                                      long usuarioId) {
        Usuario usuario = usuario(usuarioId);
        exigirVariantesSinRepetir(peticion);

        List<MovimientoInventarioDto> registrados = new ArrayList<>();

        for (PeticionesInventario.CargaInicial.Linea linea : peticion.lineas()) {
            Variante variante = servicioVariante.buscar(linea.varianteId());

            if (movimientoRepository.existsByVarianteIdAndTipo(
                    variante.getId(), TipoMovimientoInventario.CARGA_INICIAL)) {
                throw ErrorDeAplicacion.conflicto("CARGA_INICIAL_YA_REGISTRADA",
                        "La variante " + variante.getId() + " ya tiene carga inicial. Lo que venga "
                                + "después va por ajuste de inventario, que deja constancia del "
                                + "motivo.");
            }

            MovimientoInventario movimiento = movimientoRepository.save(MovimientoInventario.builder()
                    .variante(variante)
                    .tipo(TipoMovimientoInventario.CARGA_INICIAL)
                    .cantidad(linea.cantidad())
                    .costoUnitario(linea.costoUnitario())
                    .usuario(usuario)
                    .fecha(Fechas.ahora())
                    .motivo("Carga inicial de existencias")
                    .build());

            variante.setCostoPromedio(linea.costoUnitario());
            varianteRepository.save(variante);

            registrados.add(MovimientoInventarioDto.de(movimiento, linea.cantidad()));
        }

        log.info("Carga inicial de {} variante(s) por {}", registrados.size(), usuario.getNombre());
        return registrados;
    }

    /**
     * Un ajuste: alguien contó y no cuadró.
     *
     * <p>El {@code costo_unitario} del movimiento se copia del costo promedio vigente
     * de la variante, para que el movimiento cargue su propia valoración. Si no, una
     * merma de diez unidades restaría diez del stock pero cero del valor del
     * inventario, y el inventario valorado se iría separando de la realidad sin que
     * nada lo delatara.
     */
    @Transactional
    public MovimientoInventarioDto ajustar(PeticionesInventario.Ajuste peticion, long usuarioId) {
        if (peticion.cantidad() == 0) {
            throw ErrorDeAplicacion.peticionInvalida(
                    "Un ajuste de cero no ajusta nada. La cantidad va con signo: positiva si sobra, "
                            + "negativa si falta.");
        }

        Usuario usuario = usuario(usuarioId);
        Variante variante = servicioVariante.buscar(peticion.varianteId());

        MovimientoInventario movimiento = movimientoRepository.save(MovimientoInventario.builder()
                .variante(variante)
                .tipo(TipoMovimientoInventario.AJUSTE)
                .cantidad(peticion.cantidad())
                .costoUnitario(variante.getCostoPromedio())
                .usuario(usuario)
                .fecha(Fechas.ahora())
                .motivo(peticion.motivo().strip())
                .build());

        long stock = movimientoRepository.stockDe(variante.getId());
        log.info("Ajuste de {} en la variante {}: {} (stock queda en {})",
                peticion.cantidad(), variante.getId(), peticion.motivo(), stock);

        return MovimientoInventarioDto.de(movimiento, stock);
    }

    public long stockDe(long varianteId) {
        return movimientoRepository.stockDe(varianteId);
    }

    /**
     * Una variante repetida dentro del mismo lote dejaría dos cargas iniciales, que es
     * justo lo que la regla prohíbe — y el {@code exists} no lo vería, porque la
     * primera todavía no está confirmada cuando se procesa la segunda.
     */
    private void exigirVariantesSinRepetir(PeticionesInventario.CargaInicial peticion) {
        Set<Long> vistas = new HashSet<>();
        for (PeticionesInventario.CargaInicial.Linea linea : peticion.lineas()) {
            if (!vistas.add(linea.varianteId())) {
                throw ErrorDeAplicacion.peticionInvalida(
                        "La variante " + linea.varianteId() + " viene repetida en el lote.");
            }
        }
    }

    private Usuario usuario(long usuarioId) {
        return usuarioRepository.findById(usuarioId).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe el usuario " + usuarioId));
    }
}
