package com.alejandriamakeup.pos.usuarios;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * Activar y desactivar usuarios.
 *
 * <p>La regla que sostiene todo lo demás: <strong>no se puede desactivar a la última
 * DUENA activa</strong>. Sin ninguna, no queda nadie que pueda editar el catálogo,
 * ajustar inventario, ver costos ni volver a activar a nadie — y como
 * {@code /auth/configuracion-inicial} se cierra en cuanto existe un usuario (cuente
 * activo o no), tampoco hay forma de crearse una administradora nueva. El sistema
 * quedaría con la tienda dentro y la llave por fuera, sin salida desde la propia
 * aplicación.
 *
 * <p>Un usuario nunca se borra, solo se desactiva: la FK desde {@code venta},
 * {@code sesion_caja} y los ledgers es RESTRICT, y borrar a quien hizo una venta se
 * llevaría la historia con él.
 *
 * <p>Crear usuarios queda fuera de esta fase: hoy la primera nace de
 * {@code /auth/configuracion-inicial} y las demás del seed de desarrollo.
 */
@Service
@Transactional(readOnly = true)
public class ServicioUsuario {

    private static final Logger log = LoggerFactory.getLogger(ServicioUsuario.class);

    private final UsuarioRepository usuarioRepository;

    public ServicioUsuario(UsuarioRepository usuarioRepository) {
        this.usuarioRepository = usuarioRepository;
    }

    public List<Usuario> todos() {
        return usuarioRepository.findAllByOrderByNombreAsc();
    }

    @Transactional
    public Usuario cambiarActivo(long id, boolean activo) {
        Usuario usuario = usuarioRepository.findById(id).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe el usuario " + id));

        if (!activo) {
            exigirQueQuedeAlgunaDuenaActiva(usuario);
        }

        usuario.setActivo(activo);
        Usuario guardado = usuarioRepository.save(usuario);
        log.info("Usuario {} {}", usuario.getNombre(), activo ? "reactivado" : "desactivado");
        return guardado;
    }

    /**
     * Se comprueba sobre el estado actual y no sobre el resultado: si quien se va a
     * desactivar es DUENA y está activa, y es la única activa, la operación dejaría cero.
     *
     * <p>Desactivar a una DUENA ya inactiva es inofensivo y no se bloquea, para que la
     * regla no dé un error confuso ante una operación que no cambia nada.
     */
    private void exigirQueQuedeAlgunaDuenaActiva(Usuario usuario) {
        if (usuario.getRol() != Rol.DUENA || !usuario.isActivo()) {
            return;
        }

        long duenasActivas = usuarioRepository.countByRolAndActivoTrue(Rol.DUENA);
        if (duenasActivas <= 1) {
            throw ErrorDeAplicacion.conflicto("ULTIMA_DUENA_ACTIVA",
                    "No se puede desactivar a " + usuario.getNombre() + ": es la única "
                            + "administradora activa. Sin ninguna no quedaría nadie que pueda "
                            + "administrar el sistema, y no habría forma de arreglarlo desde la "
                            + "aplicación. Hay que activar antes a otra administradora.");
        }
    }
}
