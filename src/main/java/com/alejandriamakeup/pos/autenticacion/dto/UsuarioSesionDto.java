package com.alejandriamakeup.pos.autenticacion.dto;

import java.util.List;

import com.alejandriamakeup.pos.seguridad.Permiso;
import com.alejandriamakeup.pos.seguridad.PermisosPorRol;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;

/**
 * El usuario de la sesión, con sus permisos.
 *
 * <p>Los permisos van aquí para que el front pueda esconder lo que no aplica, pero
 * eso es cosmética: la autorización de verdad la impone el interceptor en el
 * backend. Nunca lleva {@code pinHash}, ni siquiera hasheado.
 */
public record UsuarioSesionDto(
        Long id,
        String nombre,
        Rol rol,
        List<Permiso> permisos) {

    public static UsuarioSesionDto de(Usuario usuario) {
        return new UsuarioSesionDto(
                usuario.getId(),
                usuario.getNombre(),
                usuario.getRol(),
                PermisosPorRol.de(usuario.getRol()).stream().sorted().toList());
    }
}
