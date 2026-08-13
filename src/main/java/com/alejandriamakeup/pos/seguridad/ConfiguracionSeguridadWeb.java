package com.alejandriamakeup.pos.seguridad;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Engancha {@link InterceptorAutorizacion} a {@code /api/**} y solo ahí.
 *
 * <p>Los recursos estáticos quedan fuera a propósito: el HTML y el JS de React
 * tienen que poder cargar para poder dibujar la pantalla de inicio de sesión. Lo
 * que se protege son los datos, que salen todos por la API.
 */
@Configuration
public class ConfiguracionSeguridadWeb implements WebMvcConfigurer {

    private final InterceptorAutorizacion interceptor;

    public ConfiguracionSeguridadWeb(InterceptorAutorizacion interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registro) {
        registro.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
