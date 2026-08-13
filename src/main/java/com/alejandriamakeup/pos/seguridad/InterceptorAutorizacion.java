package com.alejandriamakeup.pos.seguridad;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Aplica {@link ReglasDeAcceso} a toda la API.
 *
 * <p>El orden de las comprobaciones no es casual:
 *
 * <ol>
 *   <li><strong>Regla primero.</strong> Si nadie declaró la ruta, se niega. Antes
 *       incluso de mirar la sesión, porque una ruta sin regla es un bug y no un
 *       problema de credenciales.
 *   <li><strong>Configuración inicial.</strong> Sin usuarios en la base, solo pasan
 *       las tres rutas del arranque. Va antes de la autenticación porque todavía no
 *       existe nadie con quien autenticarse.
 *   <li><strong>Sesión y permiso.</strong>
 * </ol>
 *
 * <p>Las excepciones que lanza {@code preHandle} sí pasan por el
 * {@code @RestControllerAdvice}, así que los cuerpos de error salen con el mismo
 * formato que los del resto de la aplicación.
 */
@Component
public class InterceptorAutorizacion implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(InterceptorAutorizacion.class);

    private final ReglasDeAcceso reglas;
    private final UsuarioRepository usuarioRepository;

    /**
     * Pestillo de un solo sentido: arranca en falso y solo puede subir.
     *
     * <p>Antes esto era un {@code count(*)} en cada petición, para una condición que es
     * falsa para siempre desde que existe el primer usuario. Cachear el resultado sin
     * más obligaría a invalidar el caché al crear la administradora, y un caché rancio
     * dejaría la aplicación inservible — respondiendo 409 a todo, sin forma de salir
     * desde la propia app.
     *
     * <p>Un pestillo lo evita porque <strong>nunca guarda el estado negativo</strong>.
     * Mientras está en falso consulta, y solo se levanta cuando la base confirma que ya
     * hay usuarios. Si se levanta, es porque es verdad y no puede dejar de serlo: no hay
     * ninguna operación que devuelva el sistema a cero usuarios. Y si por lo que sea no
     * se levantara, el único coste es seguir consultando.
     *
     * <p>Es {@code volatile} porque Tomcat atiende peticiones en varios hilos.
     */
    private volatile boolean yaHayUsuarios;

    public InterceptorAutorizacion(ReglasDeAcceso reglas, UsuarioRepository usuarioRepository) {
        this.reglas = reglas;
        this.usuarioRepository = usuarioRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest peticion, HttpServletResponse respuesta, Object handler) {
        HttpMethod metodo = HttpMethod.valueOf(peticion.getMethod());
        String ruta = peticion.getRequestURI();

        Regla regla = reglas.para(metodo, ruta).orElseThrow(() -> {
            log.error("Ruta sin regla de acceso declarada: {} {}. Se niega por defecto. "
                    + "Hay que declararla en ReglasDeAcceso.", metodo, ruta);
            return ErrorDeAplicacion.rutaSinRegla(metodo.name(), ruta);
        });

        if (faltaLaConfiguracionInicial() && !reglas.permitidaEnConfiguracionInicial(metodo, ruta)) {
            throw ErrorDeAplicacion.configuracionInicialRequerida();
        }

        if (regla instanceof Regla.Publico) {
            return true;
        }

        Rol rol = SesionHttp.rol(peticion.getSession(false))
                .orElseThrow(ErrorDeAplicacion::noAutenticado);

        if (regla instanceof Regla.RequierePermiso requiere
                && !PermisosPorRol.puede(rol, requiere.permiso())) {
            throw ErrorDeAplicacion.sinPermiso(
                    "El rol " + rol + " no tiene el permiso " + requiere.permiso() + ".");
        }

        return true;
    }

    /**
     * Si todavía no hay ningún usuario en la base.
     *
     * <p>Cuenta <strong>todos</strong> los usuarios, activos e inactivos, y eso no es un
     * detalle: si contara solo los activos, desactivar a todo el mundo reabriría
     * {@code /auth/configuracion-inicial} y cualquiera podría crearse una DUENA nueva.
     * Un sistema sin nadie que administre nada es un problema; un sistema donde
     * cualquiera puede nombrarse administradora es otro peor.
     *
     * <p>Que nunca se pueda llegar a cero usuarios activos lo garantiza aparte
     * {@code ServicioUsuario}, que se niega a desactivar a la última DUENA activa.
     */
    private boolean faltaLaConfiguracionInicial() {
        if (yaHayUsuarios) {
            return false;
        }
        if (usuarioRepository.count() > 0) {
            yaHayUsuarios = true;
            return false;
        }
        return true;
    }
}
