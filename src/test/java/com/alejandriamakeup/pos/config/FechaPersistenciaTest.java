package com.alejandriamakeup.pos.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * El esquema dice que las fechas son TEXT en ISO-8601. Este test comprueba las
 * tres cosas que eso tiene que significar: que el valor vuelve intacto, que el
 * texto guardado es de verdad ISO-8601, y que ordenar ese texto en SQL da el
 * orden cronológico.
 *
 * <p>Lo tercero no es gratis. Si el formato no llevara relleno con ceros
 * ({@code 2026-1-5 9:00:00} en vez de {@code 2026-01-05 09:00:00}), comparar como
 * texto pondría enero después de noviembre y todo informe por rango de fechas
 * saldría mal sin que nada fallara.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
@Transactional
class FechaPersistenciaTest {

    @Autowired
    private UsuarioRepository usuarioRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void elLocalDateTimeVuelveIntactoDeLaBase() {
        LocalDateTime fecha = LocalDateTime.of(2026, 1, 5, 9, 7, 3);
        Long id = usuarioRepository.save(nuevoUsuario("ida-y-vuelta", fecha)).getId();

        entityManager.flush();
        entityManager.clear();

        Usuario releido = usuarioRepository.findById(id).orElseThrow();
        System.out.println("VERIFICACION LocalDateTime guardado=" + fecha + " releido=" + releido.getFechaCreacion());
        assertThat(releido.getFechaCreacion()).isEqualTo(fecha);
    }

    @Test
    void elTextoGuardadoEsIso8601ConRelleno() {
        LocalDateTime fecha = LocalDateTime.of(2026, 1, 5, 9, 7, 3);
        Long id = usuarioRepository.save(nuevoUsuario("texto-crudo", fecha)).getId();
        entityManager.flush();

        String crudo = (String) entityManager
                .createNativeQuery("SELECT fecha_creacion FROM usuario WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();

        System.out.println("VERIFICACION texto crudo en la columna TEXT => '" + crudo + "'");
        assertThat(crudo).isEqualTo("2026-01-05 09:07:03");
        assertThat(crudo).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    void ordenarPorFechaEnSqlDaElOrdenCronologico() {
        // Fechas elegidas para romper un formato sin relleno: enero contra
        // noviembre, día 5 contra día 20, hora 9 contra hora 10.
        List<LocalDateTime> fechas = List.of(
                LocalDateTime.of(2026, 11, 20, 10, 30, 0),
                LocalDateTime.of(2026, 1, 5, 9, 0, 0),
                LocalDateTime.of(2027, 1, 1, 0, 0, 0),
                LocalDateTime.of(2026, 1, 5, 23, 59, 59),
                LocalDateTime.of(2026, 2, 1, 0, 0, 1),
                LocalDateTime.of(2025, 12, 31, 23, 59, 59));

        String marca = "orden-" + UUID.randomUUID();
        for (int i = 0; i < fechas.size(); i++) {
            usuarioRepository.save(nuevoUsuario(marca + "-" + i, fechas.get(i)));
        }
        entityManager.flush();

        @SuppressWarnings("unchecked")
        List<String> ordenSql = entityManager
                .createNativeQuery("SELECT fecha_creacion FROM usuario WHERE nombre LIKE :marca "
                        + "ORDER BY fecha_creacion ASC")
                .setParameter("marca", marca + "%")
                .getResultList();

        List<String> ordenJava = fechas.stream()
                .sorted(Comparator.naturalOrder())
                .map(f -> f.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .toList();

        System.out.println("VERIFICACION ORDER BY en SQL => " + ordenSql);
        System.out.println("VERIFICACION orden cronologico en Java => " + ordenJava);
        assertThat(ordenSql).containsExactlyElementsOf(ordenJava);
    }

    private Usuario nuevoUsuario(String etiqueta, LocalDateTime fechaCreacion) {
        Usuario usuario = new Usuario();
        usuario.setNombre(etiqueta.startsWith("orden-") ? etiqueta : etiqueta + "-" + UUID.randomUUID());
        usuario.setPinHash("$2a$10$hashDePrueba");
        usuario.setRol(Rol.EMPLEADA);
        usuario.setActivo(true);
        usuario.setFechaCreacion(fechaCreacion);
        return usuario;
    }
}
