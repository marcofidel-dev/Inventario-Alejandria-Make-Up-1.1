package com.alejandriamakeup.pos.usuarios;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Usuarios: listar, desactivar y reactivar.
 *
 * <p>Sin verbo DELETE y sin creación. Lo primero porque un usuario borrado se llevaría
 * la historia de ventas que lo referencia; lo segundo porque crear usuarios necesita
 * manejo de PIN y queda para más adelante — hoy la primera nace de
 * {@code /auth/configuracion-inicial}.
 */
@RestController
@RequestMapping("/api/v1/usuarios")
public class UsuarioController {

    private final ServicioUsuario servicioUsuario;

    public UsuarioController(ServicioUsuario servicioUsuario) {
        this.servicioUsuario = servicioUsuario;
    }

    @GetMapping
    public List<UsuarioDto> listar() {
        return servicioUsuario.todos().stream().map(UsuarioDto::de).toList();
    }

    @PostMapping("/{id}/desactivacion")
    public UsuarioDto desactivar(@PathVariable long id) {
        return UsuarioDto.de(servicioUsuario.cambiarActivo(id, false));
    }

    @PostMapping("/{id}/reactivacion")
    public UsuarioDto reactivar(@PathVariable long id) {
        return UsuarioDto.de(servicioUsuario.cambiarActivo(id, true));
    }

    /** Un usuario visto desde la API. Nunca lleva {@code pinHash}, ni siquiera hasheado. */
    public record UsuarioDto(Long id, String nombre, Rol rol, boolean activo) {

        static UsuarioDto de(Usuario usuario) {
            return new UsuarioDto(usuario.getId(), usuario.getNombre(), usuario.getRol(),
                    usuario.isActivo());
        }
    }
}
