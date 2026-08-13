package com.alejandriamakeup.pos.catalogo;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.catalogo.dto.ResultadoActivacionDto;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * Marcas.
 *
 * <p>La unicidad del nombre la impone el índice de V4 sobre la expresión
 * normalizada. Lo que hace este servicio es comprobarla antes para poder responder
 * un 409 que <strong>nombre la marca con la que se choca</strong>: quien escribe
 * "Loréal" y recibe "ya existe LOREAL" entiende el problema, quien recibe una
 * violación de índice no.
 *
 * <p>Nunca se borra: solo {@code activo = 0}. Todas las FK son RESTRICT, así que un
 * borrado físico fallaría de todos modos; el punto es que ni se ofrezca.
 */
@Service
@Transactional(readOnly = true)
public class ServicioMarca {

    private static final Logger log = LoggerFactory.getLogger(ServicioMarca.class);

    private final MarcaRepository marcaRepository;

    public ServicioMarca(MarcaRepository marcaRepository) {
        this.marcaRepository = marcaRepository;
    }

    @Transactional
    public Marca crear(String nombre) {
        exigirNombreLibre(nombre, null);

        Marca marca = new Marca();
        marca.setNombre(nombre.strip());
        marca.setActivo(true);

        Marca guardada = marcaRepository.save(marca);
        log.info("Marca creada: {}", guardada.getNombre());
        return guardada;
    }

    @Transactional
    public Marca renombrar(long id, String nombre) {
        Marca marca = buscar(id);
        exigirNombreLibre(nombre, id);
        marca.setNombre(nombre.strip());
        return marcaRepository.save(marca);
    }

    @Transactional
    public ResultadoActivacionDto cambiarActivo(long id, boolean activo) {
        Marca marca = buscar(id);
        marca.setActivo(activo);
        marcaRepository.save(marca);
        log.info("Marca {} {}", marca.getNombre(), activo ? "reactivada" : "desactivada");
        return ResultadoActivacionDto.sinAdvertencia(marca.getId(), marca.getNombre(), activo);
    }

    public List<Marca> todas() {
        return marcaRepository.findAll();
    }

    public Marca buscar(long id) {
        return marcaRepository.findById(id).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe la marca " + id));
    }

    /**
     * Compara contra todas las marcas normalizando. Es fiable porque el pool de una
     * conexión serializa las escrituras: dos peticiones no pueden leer "libre" a la
     * vez y luego insertar las dos. Y si alguna vez esa premisa cambiara, el índice
     * de V4 sigue ahí.
     */
    private void exigirNombreLibre(String nombre, Long idQueSeExcluye) {
        String normalizado = NombreNormalizado.de(nombre);

        Optional<Marca> choque = marcaRepository.findAll().stream()
                .filter(existente -> !existente.getId().equals(idQueSeExcluye))
                .filter(existente -> NombreNormalizado.de(existente.getNombre()).equals(normalizado))
                .findFirst();

        if (choque.isPresent()) {
            throw ErrorDeAplicacion.conflicto("NOMBRE_DUPLICADO",
                    "Ya existe la marca \"" + choque.get().getNombre() + "\", que es el mismo nombre "
                            + "salvo tildes o mayúsculas.");
        }
    }
}
