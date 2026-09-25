package com.alejandriamakeup.pos.ventas.recibo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.ventas.GeneradorComprobante;
import com.alejandriamakeup.pos.ventas.Venta;
import com.alejandriamakeup.pos.ventas.VentaItem;
import com.alejandriamakeup.pos.ventas.VentaItemRepository;
import com.alejandriamakeup.pos.ventas.VentaRepository;
import com.alejandriamakeup.pos.ventas.dto.VentaDto;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * Generar, guardar y recuperar el comprobante de una venta.
 *
 * <p><strong>La venta es la verdad; el PDF es derivado.</strong> De ahí salen las dos
 * reglas que sostiene este servicio:
 *
 * <ul>
 *   <li>Se genera <strong>después</strong> del commit de la venta, en transacción
 *       propia. Nunca dentro de la del cobro: un PDF no puede hacer rollback de plata
 *       que ya entró al cajón.
 *   <li>Si la generación falla —disco lleno, permisos, la carpeta sincronizada que el
 *       cliente de nube tiene bloqueada— <strong>se registra y se sigue</strong>. La
 *       venta queda válida con {@code ruta_recibo} en nulo y se recupera después.
 *       Nunca se tumba un cobro ya realizado por un artefacto que se puede rehacer.
 * </ul>
 *
 * <p>Escribir un archivo dentro de un método {@code @Transactional} retiene la única
 * conexión del pool mientras dura el I/O. Son milisegundos sobre un archivo de dos
 * kilobytes, y la alternativa —generar fuera y abrir otra transacción para guardar la
 * ruta— sería dos transacciones para el mismo hecho.
 */
@Service
@Transactional(readOnly = true)
public class ServicioRecibo {

    private static final Logger log = LoggerFactory.getLogger(ServicioRecibo.class);

    private final VentaRepository ventaRepository;
    private final VentaItemRepository itemRepository;
    private final GeneradorComprobante generador;
    private final Path raiz;

    public ServicioRecibo(VentaRepository ventaRepository,
                          VentaItemRepository itemRepository,
                          GeneradorComprobante generador,
                          @Value("${app.paths.raiz}") String raiz) {
        this.ventaRepository = ventaRepository;
        this.itemRepository = itemRepository;
        this.generador = generador;
        this.raiz = Path.of(raiz);
    }

    /**
     * Genera el comprobante si la venta todavía no lo tiene y devuelve su ruta
     * relativa, o {@code null} si no se pudo.
     *
     * <p>Nunca lanza. Lo llama el punto de venta justo después de cobrar, y ahí una
     * excepción convertiría un cobro correcto en un error en pantalla con la clienta
     * enfrente.
     */
    @Transactional
    public String generarSiFalta(long ventaId) {
        Venta venta = buscar(ventaId);
        if (venta.getRutaRecibo() != null) {
            return venta.getRutaRecibo();
        }

        try {
            List<VentaItem> items = itemRepository.findByVentaId(venta.getId());
            String ruta = generador.generar(venta, items);
            venta.setRutaRecibo(ruta);
            ventaRepository.save(venta);
            return ruta;
        } catch (RuntimeException fallo) {
            // Con el id y el consecutivo: es lo que hace falta para encontrarla después
            // en el listado de ventas sin recibo y regenerarla.
            log.error("No se pudo generar el recibo de la venta {} (id {}). La venta es "
                            + "válida y queda sin comprobante hasta que se regenere.",
                    venta.getConsecutivo(), venta.getId(), fallo);
            return null;
        }
    }

    /**
     * El PDF ya generado.
     *
     * <p>Se comprueba que el archivo exista de verdad y no solo que la columna tenga
     * texto: la carpeta de recibos está sincronizada con la nube y vive fuera de la
     * base de datos, así que alguien pudo haberla movido. Un 404 con mensaje lleva a
     * regenerar; un flujo de bytes vacío no lleva a ninguna parte.
     */
    public byte[] pdf(long ventaId) {
        Venta venta = buscar(ventaId);
        Path archivo = archivoDe(venta);

        if (archivo == null || !Files.isRegularFile(archivo)) {
            throw ErrorDeAplicacion.noEncontrado("La venta " + venta.getConsecutivo()
                    + " no tiene recibo generado. Se puede volver a generar desde el "
                    + "listado de ventas.");
        }
        try {
            return Files.readAllBytes(archivo);
        } catch (IOException e) {
            throw ErrorDeAplicacion.noEncontrado("No se pudo leer el recibo de la venta "
                    + venta.getConsecutivo() + ": " + e.getMessage());
        }
    }

    /**
     * La ruta absoluta del PDF ya generado, para abrirlo con el visor del sistema.
     *
     * <p>Devuelve la ruta y no abre nada: abrir es lanzar un proceso, y este método es
     * transaccional. Con el pool de una conexión, esperar a que arranque un programa de
     * escritorio con la conexión tomada es exactamente lo que no se puede hacer. Quien
     * llama abre después, fuera de la transacción.
     *
     * <p>Comprueba el archivo igual que {@link #pdf(long)} y por lo mismo: la columna
     * puede tener texto y el archivo no estar, y decírselo a {@code Desktop} produce un
     * error del sistema operativo en vez de un mensaje que lleve a regenerarlo.
     */
    public Path archivo(long ventaId) {
        Venta venta = buscar(ventaId);
        Path archivo = archivoDe(venta);

        if (archivo == null || !Files.isRegularFile(archivo)) {
            throw ErrorDeAplicacion.noEncontrado("La venta " + venta.getConsecutivo()
                    + " no tiene recibo generado. Se puede volver a generar desde el "
                    + "listado de ventas.");
        }
        return archivo;
    }

    /** El nombre con el que se descarga o se abre: el consecutivo, no el id. */
    public String nombreDeArchivo(long ventaId) {
        return buscar(ventaId).getConsecutivo() + ".pdf";
    }

    /**
     * Las ventas sin comprobante, para recuperarlas en lote.
     *
     * <p>Sin filtrar por fecha y sin paginar: si hay muchas es que algo estuvo fallando
     * durante días, y justo entonces recortar la lista escondería el problema.
     */
    public List<VentaDto.Resumen> sinRecibo() {
        return ventaRepository.findByRutaReciboIsNullOrderByFechaAsc().stream()
                .map(venta -> new VentaDto.Resumen(
                        venta.getId(),
                        venta.getConsecutivo(),
                        String.valueOf(venta.getFecha()),
                        venta.getTotal(),
                        venta.getMetodoPago(),
                        venta.getEstado(),
                        venta.getUsuario().getNombre(),
                        venta.getMotivoAnulacion(),
                        venta.getRutaRecibo()))
                .toList();
    }

    private Path archivoDe(Venta venta) {
        return venta.getRutaRecibo() == null ? null : raiz.resolve(venta.getRutaRecibo());
    }

    private Venta buscar(long ventaId) {
        return ventaRepository.findById(ventaId).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe la venta " + ventaId));
    }
}
