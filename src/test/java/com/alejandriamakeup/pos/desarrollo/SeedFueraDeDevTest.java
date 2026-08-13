package com.alejandriamakeup.pos.desarrollo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.catalogo.CategoriaRepository;
import com.alejandriamakeup.pos.catalogo.MarcaRepository;
import com.alejandriamakeup.pos.catalogo.ProductoRepository;
import com.alejandriamakeup.pos.catalogo.VarianteRepository;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * El seed de desarrollo, con sus PINs 1234 y 5678, jamás puede llegar a producción.
 *
 * <p>Tres cerraduras y un test para cada una. No es paranoia proporcional al riesgo:
 * el resultado de que falle es una tienda real con dos usuarias de PIN público, y el
 * fallo sería invisible — la aplicación arrancaría perfecta, con dos usuarias más de
 * las que debería.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
class SeedFueraDeDevTest {

    private static final List<String> PINES_DE_DESARROLLO = List.of("1234", "5678");

    @Autowired
    private ApplicationContext contexto;

    @Autowired
    private Environment entorno;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private MarcaRepository marcaRepository;

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Autowired
    private ProductoRepository productoRepository;

    @Autowired
    private VarianteRepository varianteRepository;

    /** Cerradura 1: sin el perfil {@code dev}, el bean ni se crea. */
    @Test
    void sinElPerfilDevElSeedNiExisteComoBean() {
        String[] beans = contexto.getBeanNamesForType(SeedDesarrollo.class);

        System.out.println("VERIFICACION perfiles activos " + List.of(entorno.getActiveProfiles())
                + ", beans SeedDesarrollo => " + List.of(beans));
        assertThat(beans).isEmpty();
    }

    /**
     * Cerradura 2: aunque alguien borre el {@code @Profile("dev")} en un refactor, el
     * seed se niega a correr y lo dice.
     *
     * <p>Se invoca directamente porque de eso se trata: simular que la anotación
     * desapareció y el runner se ejecutó de todas formas.
     */
    @Test
    void invocadoFueraDeDevElSeedSeNiegaAcorrer() {
        SeedDesarrollo seed = new SeedDesarrollo(entorno, usuarioRepository, marcaRepository,
                categoriaRepository, productoRepository, varianteRepository);

        assertThatThrownBy(() -> seed.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev")
                .hasMessageContaining("PIN conocido");

        System.out.println("VERIFICACION seed invocado sin perfil dev => se negó con IllegalStateException");
    }

    /**
     * Cerradura 3, la que de verdad importa: no existe en la base ningún usuario cuyo
     * PIN sea uno de los de desarrollo.
     *
     * <p>Se comprueba contra el hash con BCrypt y no por nombre de usuario, porque lo
     * peligroso no es que exista una usuaria llamada "Camila" — es que exista
     * cualquiera cuyo PIN sea 1234.
     */
    @Test
    void ningunUsuarioDeLaBaseTieneUnPinDeDesarrollo() {
        BCryptPasswordEncoder codificador = new BCryptPasswordEncoder();
        List<Usuario> usuarios = usuarioRepository.findAll();

        List<String> comprometidos = usuarios.stream()
                .filter(usuario -> PINES_DE_DESARROLLO.stream()
                        .anyMatch(pin -> codificador.matches(pin, usuario.getPinHash())))
                .map(Usuario::getNombre)
                .toList();

        System.out.println("VERIFICACION " + usuarios.size() + " usuarios en la base, con PIN de desarrollo => "
                + (comprometidos.isEmpty() ? "ninguno" : comprometidos));
        assertThat(comprometidos)
                .withFailMessage("Estos usuarios tienen un PIN de desarrollo: %s", comprometidos)
                .isEmpty();
    }
}
