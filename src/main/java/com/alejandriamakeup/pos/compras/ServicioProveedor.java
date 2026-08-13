package com.alejandriamakeup.pos.compras;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.catalogo.NombreNormalizado;
import com.alejandriamakeup.pos.catalogo.dto.ResultadoActivacionDto;
import com.alejandriamakeup.pos.compras.dto.PeticionesCompras;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * Proveedores.
 *
 * <p>Mismo patrón que {@code ServicioMarca}, y por las mismas razones. La unicidad
 * del nombre la impone el índice normalizado de la V5; aquí se comprueba antes para
 * poder responder un 409 que <strong>nombre al proveedor con el que se choca</strong>.
 * Quien escribe "Distribuciones Lopez" y recibe "ya existe Distribuciones López"
 * entiende el problema; quien recibe una violación de índice, no.
 *
 * <p>Nunca se borra: solo {@code activo = 0}. Las FK son RESTRICT, así que un borrado
 * físico fallaría igual en cuanto el proveedor tuviera una compra — pero el punto es
 * que ni se ofrezca, porque borrarlo se llevaría por delante el historial de a quién
 * se le compró qué.
 */
@Service
@Transactional(readOnly = true)
public class ServicioProveedor {

    private static final Logger log = LoggerFactory.getLogger(ServicioProveedor.class);

    private final ProveedorRepository proveedorRepository;

    public ServicioProveedor(ProveedorRepository proveedorRepository) {
        this.proveedorRepository = proveedorRepository;
    }

    @Transactional
    public Proveedor crear(PeticionesCompras.Proveedor peticion) {
        exigirNombreLibre(peticion.nombre(), null);

        Proveedor proveedor = new Proveedor();
        aplicar(proveedor, peticion);
        proveedor.setActivo(true);
        proveedor.setFechaCreacion(Fechas.ahora());

        Proveedor guardado = proveedorRepository.save(proveedor);
        log.info("Proveedor creado: {}", guardado.getNombre());
        return guardado;
    }

    @Transactional
    public Proveedor actualizar(long id, PeticionesCompras.Proveedor peticion) {
        Proveedor proveedor = buscar(id);
        exigirNombreLibre(peticion.nombre(), id);
        aplicar(proveedor, peticion);
        return proveedorRepository.save(proveedor);
    }

    @Transactional
    public ResultadoActivacionDto cambiarActivo(long id, boolean activo) {
        Proveedor proveedor = buscar(id);
        proveedor.setActivo(activo);
        proveedorRepository.save(proveedor);
        log.info("Proveedor {} {}", proveedor.getNombre(), activo ? "reactivado" : "desactivado");
        return ResultadoActivacionDto.sinAdvertencia(
                proveedor.getId(), proveedor.getNombre(), activo);
    }

    public List<Proveedor> todos() {
        return proveedorRepository.findAll();
    }

    public Proveedor buscar(long id) {
        return proveedorRepository.findById(id).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe el proveedor " + id));
    }

    private void aplicar(Proveedor proveedor, PeticionesCompras.Proveedor peticion) {
        proveedor.setNombre(peticion.nombre().strip());
        proveedor.setNit(vacioComoNulo(peticion.nit()));
        proveedor.setTelefono(vacioComoNulo(peticion.telefono()));
        proveedor.setContacto(vacioComoNulo(peticion.contacto()));
        proveedor.setNotas(vacioComoNulo(peticion.notas()));
    }

    /**
     * Compara contra todos los proveedores normalizando. Es fiable porque el pool de
     * una conexión serializa las escrituras: dos peticiones no pueden leer "libre" a
     * la vez y luego insertar las dos. Y si esa premisa cambiara, el índice de la V5
     * sigue ahí debajo.
     */
    private void exigirNombreLibre(String nombre, Long idQueSeExcluye) {
        String normalizado = NombreNormalizado.de(nombre);

        Optional<Proveedor> choque = proveedorRepository.findAll().stream()
                .filter(existente -> !existente.getId().equals(idQueSeExcluye))
                .filter(existente -> NombreNormalizado.de(existente.getNombre()).equals(normalizado))
                .findFirst();

        if (choque.isPresent()) {
            throw ErrorDeAplicacion.conflicto("NOMBRE_DUPLICADO",
                    "Ya existe el proveedor \"" + choque.get().getNombre() + "\", que es el mismo "
                            + "nombre salvo tildes o mayúsculas.");
        }
    }

    /** Un campo opcional que llega vacío es ausencia, no una cadena vacía guardada. */
    private String vacioComoNulo(String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
