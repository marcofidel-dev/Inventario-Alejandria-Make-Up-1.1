package com.alejandriamakeup.pos.usuarios;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByNombre(String nombre);

    List<Usuario> findByActivoTrue();

    List<Usuario> findAllByOrderByNombreAsc();

    /**
     * Cuántas personas con este rol quedan activas. Se usa para negarse a desactivar a
     * la última DUENA activa: sin ninguna, no quedaría nadie que pueda administrar el
     * sistema y no habría forma de arreglarlo desde la propia aplicación.
     */
    long countByRolAndActivoTrue(Rol rol);
}
