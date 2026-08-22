package com.alejandriamakeup.pos.catalogo;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.catalogo.dto.CatalogoDto;
import com.alejandriamakeup.pos.catalogo.dto.ResultadoActivacionDto;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/** Categorías. Mismas reglas que {@link ServicioMarca}: nunca se borra, nombre único normalizado. */
@Service
@Transactional(readOnly = true)
public class ServicioCategoria {

    private static final Logger log = LoggerFactory.getLogger(ServicioCategoria.class);

    private final CategoriaRepository categoriaRepository;

    public ServicioCategoria(CategoriaRepository categoriaRepository) {
        this.categoriaRepository = categoriaRepository;
    }

    /** Uso interno entre servicios y pruebas. Desde la API va {@link #crearDto(String)}. */
    @Transactional
    public Categoria crear(String nombre) {
        exigirNombreLibre(nombre, null);

        Categoria categoria = new Categoria();
        categoria.setNombre(nombre.strip());
        categoria.setActivo(true);

        Categoria guardada = categoriaRepository.save(categoria);
        log.info("Categoría creada: {}", guardada.getNombre());
        return guardada;
    }

    /** Uso interno. Desde la API va {@link #renombrarDto(long, String)}. */
    @Transactional
    public Categoria renombrar(long id, String nombre) {
        Categoria categoria = buscarEntidad(id);
        exigirNombreLibre(nombre, id);
        categoria.setNombre(nombre.strip());
        return categoriaRepository.save(categoria);
    }

    @Transactional
    public ResultadoActivacionDto cambiarActivo(long id, boolean activo) {
        Categoria categoria = buscarEntidad(id);
        categoria.setActivo(activo);
        categoriaRepository.save(categoria);
        log.info("Categoría {} {}", categoria.getNombre(), activo ? "reactivada" : "desactivada");
        return ResultadoActivacionDto.sinAdvertencia(categoria.getId(), categoria.getNombre(), activo);
    }

    /**
     * Lo que responde el endpoint. El mapeo a DTO vive aquí y no en el controlador
     * porque las asociaciones son LAZY: mapearlas con la sesión ya cerrada lanza
     * {@code LazyInitializationException}, que en el mostrador se ve como un 500 al
     * guardar. Dentro de la transacción es un acceso normal.
     */
    @Transactional
    public CatalogoDto.CategoriaDto crearDto(String nombre) {
        return aDto(crear(nombre));
    }

    /** Ver {@link #crearDto(String)}: el mapeo va dentro de la transacción. */
    @Transactional
    public CatalogoDto.CategoriaDto renombrarDto(long id, String nombre) {
        return aDto(renombrar(id, nombre));
    }

    private CatalogoDto.CategoriaDto aDto(Categoria categoria) {
        return new CatalogoDto.CategoriaDto(categoria.getId(), categoria.getNombre(),
                categoria.isActivo());
    }

    /**
     * Uso interno entre servicios, nunca desde un controlador: devuelve la entidad
     * de persistencia, no un DTO. Exponerla en un endpoint arrastra a la API los
     * campos y las relaciones perezosas del modelo. Lo impide
     * {@code ControladoresNoDevuelvenEntidadesTest}.
     */
    public Categoria buscarEntidad(long id) {
        return categoriaRepository.findById(id).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe la categoría " + id));
    }

    private void exigirNombreLibre(String nombre, Long idQueSeExcluye) {
        String normalizado = NombreNormalizado.de(nombre);

        Optional<Categoria> choque = categoriaRepository.findAll().stream()
                .filter(existente -> !existente.getId().equals(idQueSeExcluye))
                .filter(existente -> NombreNormalizado.de(existente.getNombre()).equals(normalizado))
                .findFirst();

        if (choque.isPresent()) {
            throw ErrorDeAplicacion.conflicto("NOMBRE_DUPLICADO",
                    "Ya existe la categoría \"" + choque.get().getNombre() + "\", que es el mismo "
                            + "nombre salvo tildes o mayúsculas.");
        }
    }
}
