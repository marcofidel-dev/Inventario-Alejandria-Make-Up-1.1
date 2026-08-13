package com.alejandriamakeup.pos.compras;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.catalogo.ServicioVariante;
import com.alejandriamakeup.pos.catalogo.Variante;
import com.alejandriamakeup.pos.compras.dto.CompraDto;
import com.alejandriamakeup.pos.compras.dto.PeticionesCompras;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.consecutivos.ServicioConsecutivo;
import com.alejandriamakeup.pos.consecutivos.TipoConsecutivo;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * El borrador de una compra: capturar la factura del proveedor, corregirla y, si no
 * iba, descartarla. Nada de esto toca el inventario — eso empieza en
 * {@link ServicioRecepcion}.
 *
 * <p><strong>El total lo calcula este servicio, nunca el cliente.</strong> La
 * petición ni siquiera tiene campo para mandarlo. El número que la pantalla muestra
 * mientras se teclea es una ayuda para el ojo; el que queda guardado sale de sumar
 * las líneas aquí, y es el que la pantalla vuelve a mostrar después de guardar.
 */
@Service
@Transactional(readOnly = true)
public class ServicioCompra {

    private static final Logger log = LoggerFactory.getLogger(ServicioCompra.class);

    private final CompraRepository compraRepository;
    private final CompraItemRepository compraItemRepository;
    private final ServicioProveedor servicioProveedor;
    private final ServicioVariante servicioVariante;
    private final ServicioConsecutivo servicioConsecutivo;
    private final UsuarioRepository usuarioRepository;

    public ServicioCompra(CompraRepository compraRepository,
                          CompraItemRepository compraItemRepository,
                          ServicioProveedor servicioProveedor,
                          ServicioVariante servicioVariante,
                          ServicioConsecutivo servicioConsecutivo,
                          UsuarioRepository usuarioRepository) {
        this.compraRepository = compraRepository;
        this.compraItemRepository = compraItemRepository;
        this.servicioProveedor = servicioProveedor;
        this.servicioVariante = servicioVariante;
        this.servicioConsecutivo = servicioConsecutivo;
        this.usuarioRepository = usuarioRepository;
    }

    @Transactional
    public CompraDto crearBorrador(PeticionesCompras.Compra peticion, long usuarioId) {
        Proveedor proveedor = servicioProveedor.buscar(peticion.proveedorId());
        if (!proveedor.isActivo()) {
            throw ErrorDeAplicacion.conflicto("PROVEEDOR_INACTIVO",
                    "El proveedor \"" + proveedor.getNombre() + "\" está desactivado. Hay que "
                            + "reactivarlo antes de registrarle una compra.");
        }

        Compra compra = new Compra();
        compra.setConsecutivo(servicioConsecutivo.siguiente(TipoConsecutivo.COMPRA));
        compra.setProveedor(proveedor);
        compra.setUsuario(usuario(usuarioId));
        compra.setNumeroFactura(vacioComoNulo(peticion.numeroFactura()));
        compra.setNotas(vacioComoNulo(peticion.notas()));
        compra.setEstado(EstadoCompra.BORRADOR);
        compra.setFecha(Fechas.ahora());
        compra.setTotal(0);
        Compra guardada = compraRepository.save(compra);

        List<CompraItem> items = reemplazarItems(guardada, peticion.lineas());

        log.info("Compra {} en borrador: {} línea(s) por {}",
                guardada.getConsecutivo(), items.size(), guardada.getTotal());
        return CompraDto.de(guardada, items);
    }

    @Transactional
    public CompraDto actualizarBorrador(long id, PeticionesCompras.Compra peticion) {
        Compra compra = buscar(id);
        exigirEstado(compra, EstadoCompra.BORRADOR,
                "Solo se puede editar una compra en borrador.");

        Proveedor proveedor = servicioProveedor.buscar(peticion.proveedorId());
        compra.setProveedor(proveedor);
        compra.setNumeroFactura(vacioComoNulo(peticion.numeroFactura()));
        compra.setNotas(vacioComoNulo(peticion.notas()));

        List<CompraItem> items = reemplazarItems(compra, peticion.lineas());
        return CompraDto.de(compra, items);
    }

    /**
     * Descartar un borrador. No revierte nada porque no hubo mercancía: es el acto de
     * decir "esta factura no iba". El consecutivo queda consumido a propósito — la
     * serie no tiene huecos, tiene una compra que se puede mirar y que dice por qué
     * no siguió.
     */
    @Transactional
    public CompraDto descartar(long id, String motivo, long usuarioId) {
        Compra compra = buscar(id);
        exigirEstado(compra, EstadoCompra.BORRADOR,
                "Solo se puede descartar una compra en borrador. Una compra recibida se anula, "
                        + "que es distinto: hay stock que devolver.");

        compra.darDeBaja(EstadoCompra.DESCARTADA, motivo.strip(), usuario(usuarioId), Fechas.ahora());
        compraRepository.save(compra);

        log.info("Compra {} descartada: {}", compra.getConsecutivo(), motivo);
        return CompraDto.de(compra, itemsDe(compra.getId()));
    }

    /**
     * Todas las compras, de la más reciente a la más vieja.
     *
     * <p>Sin paginación, como todo listado del proyecto: se cargan completas y la
     * pantalla filtra en memoria. Es también la que decide poner los borradores
     * primero, porque son los que exigen acción.
     */
    public List<CompraDto> listar() {
        return compraRepository.findAll().stream()
                .sorted(Comparator.comparing(Compra::getFecha).reversed()
                        .thenComparing(Comparator.comparing(Compra::getId).reversed()))
                .map(compra -> CompraDto.de(compra, itemsDe(compra.getId())))
                .toList();
    }

    public CompraDto porId(long id) {
        Compra compra = buscar(id);
        return CompraDto.de(compra, itemsDe(compra.getId()));
    }

    public Compra buscar(long id) {
        return compraRepository.findById(id).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe la compra " + id));
    }

    public List<CompraItem> itemsDe(long compraId) {
        return compraItemRepository.findByCompraId(compraId);
    }

    /**
     * Deja la compra con exactamente estas líneas y recalcula el total.
     *
     * <p>Se borran las anteriores y se insertan las nuevas. Borrar aquí es legítimo y
     * no rompe la regla de append-only: esa regla es de los <em>ledgers</em>
     * —{@code MovimientoInventario} y {@code MovimientoCaja}— porque son la verdad
     * contable. Un borrador es una factura que se está copiando y todavía no ha
     * afectado a nada.
     */
    private List<CompraItem> reemplazarItems(Compra compra, List<PeticionesCompras.Compra.Linea> lineas) {
        compraItemRepository.deleteAll(itemsDe(compra.getId()));

        List<CompraItem> items = new ArrayList<>();
        long total = 0;

        for (PeticionesCompras.Compra.Linea linea : lineas) {
            Variante variante = servicioVariante.buscar(linea.varianteId());

            // El subtotal se calcula, no se recibe. Una linea repetida de la misma
            // variante es legitima: una factura real trae dos lotes del mismo tono a
            // precios distintos.
            long subtotal = (long) linea.cantidad() * linea.costoUnitario();

            CompraItem item = new CompraItem();
            item.setCompra(compra);
            item.setVariante(variante);
            item.setCantidad(linea.cantidad());
            item.setCostoUnitario(linea.costoUnitario());
            item.setSubtotal(subtotal);
            items.add(compraItemRepository.save(item));

            total += subtotal;
        }

        compra.setTotal(total);
        compraRepository.save(compra);
        return items;
    }

    private void exigirEstado(Compra compra, EstadoCompra esperado, String explicacion) {
        if (compra.getEstado() != esperado) {
            throw ErrorDeAplicacion.conflicto("ESTADO_DE_COMPRA_INVALIDO",
                    "La compra " + compra.getConsecutivo() + " está en estado "
                            + compra.getEstado() + ". " + explicacion);
        }
    }

    private Usuario usuario(long usuarioId) {
        return usuarioRepository.findById(usuarioId).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe el usuario " + usuarioId));
    }

    private String vacioComoNulo(String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
