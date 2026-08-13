package com.alejandriamakeup.pos.autenticacion;

import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.alejandriamakeup.pos.autenticacion.dto.ConfiguracionInicialPeticion;
import com.alejandriamakeup.pos.autenticacion.dto.EstadoAutenticacionDto;
import com.alejandriamakeup.pos.autenticacion.dto.LoginPeticion;
import com.alejandriamakeup.pos.autenticacion.dto.UsuarioSesionDto;
import com.alejandriamakeup.pos.seguridad.SesionHttp;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AutenticacionController {

    private final ServicioAutenticacion servicio;
    private final UsuarioRepository usuarioRepository;

    public AutenticacionController(ServicioAutenticacion servicio, UsuarioRepository usuarioRepository) {
        this.servicio = servicio;
        this.usuarioRepository = usuarioRepository;
    }

    /**
     * Los nombres de quienes pueden entrar, para que la pantalla de login los ofrezca en
     * vez de pedir que se teclee el nombre.
     *
     * <p><strong>Solo el nombre.</strong> El rol no le sirve a quien está entrando — ya
     * sabe quién es — y publicarlo le diría a cualquiera que alcance el puerto cuál de
     * las dos cuentas administra el sistema. La app se sirve en {@code 0.0.0.0} para que
     * el celular la alcance por la wifi de la tienda, así que "cualquiera en la wifi" no
     * es hipotético.
     *
     * <p>Solo los activos: un usuario desactivado no puede iniciar sesión, y ofrecerlo
     * sería invitar a intentos fallidos que además cuentan para el bloqueo.
     */
    @GetMapping("/perfiles")
    public List<PerfilDto> perfiles() {
        return usuarioRepository.findByActivoTrue().stream()
                .map(usuario -> new PerfilDto(usuario.getNombre()))
                .sorted(Comparator.comparing(PerfilDto::nombre))
                .toList();
    }

    /** Un nombre para elegir en el login. Nada más. */
    public record PerfilDto(String nombre) {
    }

    @GetMapping("/estado")
    public EstadoAutenticacionDto estado(HttpServletRequest peticion) {
        HttpSession sesion = peticion.getSession(false);
        return new EstadoAutenticacionDto(
                servicio.requiereConfiguracionInicial(),
                SesionHttp.usuarioId(sesion).isPresent());
    }

    @PostMapping("/configuracion-inicial")
    @ResponseStatus(HttpStatus.CREATED)
    public UsuarioSesionDto configuracionInicial(@Valid @RequestBody ConfiguracionInicialPeticion peticion) {
        return UsuarioSesionDto.de(
                servicio.crearAdministradoraInicial(peticion.nombre(), peticion.pin()));
    }

    @PostMapping("/login")
    public UsuarioSesionDto login(@Valid @RequestBody LoginPeticion peticion,
                                  HttpServletRequest solicitud) {
        Usuario usuario = servicio.autenticar(peticion.nombre(), peticion.pin());

        // Rotar el id de sesión al autenticar cierra la fijación de sesión: un id
        // obtenido antes del login deja de servir. Solo si ya había una sesión —
        // changeSessionId() lanza IllegalStateException cuando no hay ninguna, que
        // es justo el caso del primer login.
        if (solicitud.getSession(false) != null) {
            solicitud.changeSessionId();
        }
        SesionHttp.iniciar(solicitud.getSession(true), usuario);

        return UsuarioSesionDto.de(usuario);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest solicitud) {
        HttpSession sesion = solicitud.getSession(false);
        if (sesion != null) {
            sesion.invalidate();
        }
    }

    @GetMapping("/sesion")
    public UsuarioSesionDto sesionActual(HttpSession sesion) {
        long usuarioId = SesionHttp.usuarioIdObligatorio(sesion);
        return UsuarioSesionDto.de(usuarioRepository.findById(usuarioId)
                .orElseThrow(ErrorDeAplicacion::noAutenticado));
    }
}
