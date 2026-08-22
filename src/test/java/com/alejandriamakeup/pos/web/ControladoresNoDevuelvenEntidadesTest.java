package com.alejandriamakeup.pos.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.alejandriamakeup.pos.PosApplication;

import jakarta.persistence.Entity;

/**
 * El test que hace imposible que una entidad se escape por la API.
 *
 * <p>Recorre por reflexión todos los métodos públicos de los {@code @RestController}
 * y exige que ninguno declare como tipo de retorno una {@code @Entity}, ni
 * directamente ni envuelta en {@code List}, {@code ResponseEntity} o cualquier otro
 * genérico. Serializar una entidad arrastra a la API los campos y las relaciones
 * perezosas del modelo de persistencia: lo que hoy es un objeto de más mañana es un
 * costo filtrado, y un cambio de esquema se convierte en un cambio de contrato sin
 * que nadie lo decida. Los servicios sí se pasan entidades entre ellos —para eso
 * están los {@code buscarEntidad(id)}—; el límite está en el controlador.
 *
 * <p>Es el equivalente de {@code ReglasDeAccesoTest} para la forma de las respuestas:
 * un controlador nuevo que devuelva entidad rompe el build en vez de romper la tienda.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
class ControladoresNoDevuelvenEntidadesTest {

    @Autowired
    private RequestMappingHandlerMapping mapeos;

    @Test
    void ningunMetodoPublicoDeUnControladorDevuelveUnaEntidad() {
        List<String> culpables = new ArrayList<>();
        List<String> revisados = new ArrayList<>();

        for (Class<?> controlador : controladores()) {
            for (Method metodo : controlador.getDeclaredMethods()) {
                if (!Modifier.isPublic(metodo.getModifiers()) || metodo.isSynthetic()) {
                    continue;
                }
                String firma = controlador.getSimpleName() + "." + metodo.getName() + "()";
                List<String> entidades = entidadesEn(metodo.getGenericReturnType());
                if (entidades.isEmpty()) {
                    revisados.add(firma);
                } else {
                    culpables.add(firma + " devuelve " + entidades);
                }
            }
        }

        System.out.println("VERIFICACION métodos de controlador que devuelven DTO ("
                + revisados.size() + "):");
        revisados.stream().sorted().forEach(m -> System.out.println("    " + m));

        assertThat(culpables)
                .withFailMessage("Estos métodos de controlador exponen entidades de "
                        + "persistencia en la API; devuelvan un DTO: %s", culpables)
                .isEmpty();
        assertThat(revisados).isNotEmpty();
    }

    /** Las clases anotadas con {@code @RestController} que Spring tiene mapeadas. */
    private Set<Class<?>> controladores() {
        Set<Class<?>> clases = new LinkedHashSet<>();
        mapeos.getHandlerMethods().forEach((info, handler) -> {
            Class<?> tipo = handler.getBeanType();
            if (AnnotatedElementUtils.hasAnnotation(tipo, RestController.class)) {
                clases.add(tipo);
            }
        });
        return clases;
    }

    /**
     * Las entidades que aparecen en un tipo, mirando también dentro de los genéricos:
     * {@code List<Variante>} y {@code ResponseEntity<List<Variante>>} cuentan igual
     * que {@code Variante} a secas.
     */
    private List<String> entidadesEn(Type tipo) {
        List<String> encontradas = new ArrayList<>();
        recolectar(tipo, encontradas);
        return encontradas;
    }

    private void recolectar(Type tipo, List<String> encontradas) {
        switch (tipo) {
            case Class<?> clase -> {
                Class<?> componente = clase.isArray() ? clase.getComponentType() : clase;
                if (esEntidad(componente)) {
                    encontradas.add(componente.getSimpleName());
                }
            }
            case ParameterizedType generico -> {
                recolectar(generico.getRawType(), encontradas);
                for (Type argumento : generico.getActualTypeArguments()) {
                    recolectar(argumento, encontradas);
                }
            }
            case WildcardType comodin -> {
                for (Type limite : comodin.getUpperBounds()) {
                    recolectar(limite, encontradas);
                }
            }
            default -> {
                // Variables de tipo y demás: no nombran una entidad concreta.
            }
        }
    }

    private boolean esEntidad(AnnotatedElement tipo) {
        return AnnotatedElementUtils.hasAnnotation(tipo, Entity.class);
    }
}
