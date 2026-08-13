package com.alejandriamakeup.pos.catalogo;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public Categoria renombrar(long id, String nombre) {
        Categoria categoria = buscar(id);
        exigirNombreLibre(nombre, id);
        categoria.setNombre(nombre.strip());
        return categoriaRepository.save(categoria);
    }

    @Transactional
    public ResultadoActivacionDto cambiarActivo(long id, boolean activo) {
        Categoria categoria = buscar(id);
        categoria.setActivo(activo);
        categoriaRepository.save(categoria);
        log.info("Categoría {} {}", categoria.getNombre(), activo ? "reactivada" : "desactivada");
        return ResultadoActivacionDto.sinAdvertencia(categoria.getId(), categoria.getNombre(), activo);
    }

    public List<Categoria> todas() {
        return categoriaRepository.findAll();
    }

    public Categoria buscar(long id) {
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
