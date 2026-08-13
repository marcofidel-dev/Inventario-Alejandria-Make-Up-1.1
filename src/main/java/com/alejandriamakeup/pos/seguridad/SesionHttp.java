package com.alejandriamakeup.pos.seguridad;

import java.util.Optional;

import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

import jakarta.servlet.http.HttpSession;

/**
 * La sesión del usuario, guardada en la {@link HttpSession} del contenedor.
 *
 * <p>Tomcat ya mantiene las sesiones en memoria, que es exactamente lo que hace
 * falta en una app local de un solo PC. Spring Session existe para sacar la sesión
 * del contenedor — Redis, JDBC — y aquí no habría nada que sacar.
 *
 * <p>Se guarda el id y el rol, no la entidad: una entidad JPA en la sesión se
 * queda vieja y arrastra su contexto de persistencia.
 */
public final class SesionHttp {

    private static final String ATRIBUTO_USUARIO = "usuarioId";
    private static final String ATRIBUTO_NOMBRE = "usuarioNombre";
    private static final String ATRIBUTO_ROL = "rol";

    private SesionHttp() {
    }

    public static void iniciar(HttpSession sesion, Usuario usuario) {
        sesion.setAttribute(ATRIBUTO_USUARIO, usuario.getId());
        sesion.setAttribute(ATRIBUTO_NOMBRE, usuario.getNombre());
        sesion.setAttribute(ATRIBUTO_ROL, usuario.getRol());
    }

    public static Optional<Long> usuarioId(HttpSession sesion) {
        return atributo(sesion, ATRIBUTO_USUARIO, Long.class);
    }

    public static Optional<String> nombre(HttpSession sesion) {
        return atributo(sesion, ATRIBUTO_NOMBRE, String.class);
    }

    public static Optional<Rol> rol(HttpSession sesion) {
        return atributo(sesion, ATRIBUTO_ROL, Rol.class);
    }

    /** El id del usuario autenticado, o 401 si no hay sesión. */
    public static long usuarioIdObligatorio(HttpSession sesion) {
        return usuarioId(sesion).orElseThrow(ErrorDeAplicacion::noAutenticado);
    }

    /** El rol del usuario autenticado, o 401 si no hay sesión. */
    public static Rol rolObligatorio(HttpSession sesion) {
        return rol(sesion).orElseThrow(ErrorDeAplicacion::noAutenticado);
    }

    private static <T> Optional<T> atributo(HttpSession sesion, String nombre, Class<T> tipo) {
        if (sesion == null) {
            return Optional.empty();
        }
        Object valor = sesion.getAttribute(nombre);
        return tipo.isInstance(valor) ? Optional.of(tipo.cast(valor)) : Optional.empty();
    }
}
