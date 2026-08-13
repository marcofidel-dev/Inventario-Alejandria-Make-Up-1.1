package com.alejandriamakeup.pos.desarrollo;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.catalogo.Categoria;
import com.alejandriamakeup.pos.catalogo.CategoriaRepository;
import com.alejandriamakeup.pos.catalogo.Marca;
import com.alejandriamakeup.pos.catalogo.MarcaRepository;
import com.alejandriamakeup.pos.catalogo.Producto;
import com.alejandriamakeup.pos.catalogo.ProductoRepository;
import com.alejandriamakeup.pos.catalogo.Variante;
import com.alejandriamakeup.pos.catalogo.VarianteRepository;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * Datos de arranque para desarrollo. <strong>Nunca en una migración Flyway</strong>:
 * una migración corre también en el PC de la tienda, y el negocio no quiere
 * marcas de prueba en su catálogo ni usuarios con PIN conocido.
 *
 * <p>Solo se activa con el perfil {@code dev} y es idempotente: si ya hay
 * usuarios, no hace nada, así que reiniciar la app no duplica el catálogo.
 *
 * <p>No siembra ventas ni movimientos de inventario a propósito: eso exigiría una
 * sesión de caja abierta y consecutivos quemados, que es trabajo del servicio de
 * ventas cuando exista.
 */
@Component
@Profile("dev")
public class SeedDesarrollo implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDesarrollo.class);

    private static final String PERFIL_DEV = "dev";
    private static final String PIN_DUENA = "1234";
    private static final String PIN_EMPLEADA = "5678";

    private final Environment entorno;
    private final UsuarioRepository usuarioRepository;
    private final MarcaRepository marcaRepository;
    private final CategoriaRepository categoriaRepository;
    private final ProductoRepository productoRepository;
    private final VarianteRepository varianteRepository;

    public SeedDesarrollo(Environment entorno,
                          UsuarioRepository usuarioRepository,
                          MarcaRepository marcaRepository,
                          CategoriaRepository categoriaRepository,
                          ProductoRepository productoRepository,
                          VarianteRepository varianteRepository) {
        this.entorno = entorno;
        this.usuarioRepository = usuarioRepository;
        this.marcaRepository = marcaRepository;
        this.categoriaRepository = categoriaRepository;
        this.productoRepository = productoRepository;
        this.varianteRepository = varianteRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        exigirPerfilDev();

        if (usuarioRepository.count() > 0) {
            log.info("Seed de desarrollo omitido: ya hay {} usuarios", usuarioRepository.count());
            return;
        }

        LocalDateTime ahora = Fechas.ahora();
        BCryptPasswordEncoder codificador = new BCryptPasswordEncoder();

        usuarioRepository.save(usuario("Alejandra", Rol.DUENA, codificador.encode(PIN_DUENA), ahora));
        usuarioRepository.save(usuario("Camila", Rol.EMPLEADA, codificador.encode(PIN_EMPLEADA), ahora));

        Marca maybelline = marcaRepository.save(marca("Maybelline"));
        Marca essence = marcaRepository.save(marca("Essence"));
        Categoria labios = categoriaRepository.save(categoria("Labios"));
        Categoria ojos = categoriaRepository.save(categoria("Ojos"));

        Producto labial = productoRepository.save(
                producto("Labial mate Superstay", maybelline, labios, ahora));
        varianteRepository.save(variante(labial, "Rojo clásico", "5 ml", 38900, 21000, 3, ahora));
        varianteRepository.save(variante(labial, "Nude rosado", "5 ml", 38900, 21000, 3, ahora));

        Producto mascara = productoRepository.save(
                producto("Máscara de pestañas Lash Princess", essence, ojos, ahora));
        varianteRepository.save(variante(mascara, "Negro", "12 ml", 24900, 12500, 5, ahora));

        Producto delineador = productoRepository.save(
                producto("Delineador líquido", essence, ojos, ahora));
        varianteRepository.save(variante(delineador, "Negro intenso", null, 19900, 9800, 4, ahora));
        varianteRepository.save(variante(delineador, "Café", null, 19900, 9800, 2, ahora));

        log.info("Seed de desarrollo aplicado: {} usuarios, {} marcas, {} categorias, {} productos, {} variantes",
                usuarioRepository.count(), marcaRepository.count(), categoriaRepository.count(),
                productoRepository.count(), varianteRepository.count());
        log.info("PINs de desarrollo — Alejandria (DUENA): {} / Camila (EMPLEADA): {}", PIN_DUENA, PIN_EMPLEADA);
    }

    /**
     * Segunda cerradura, además de {@code @Profile("dev")}.
     *
     * <p>La anotación es una sola línea que alguien puede borrar en un refactor sin
     * que nada proteste, y el resultado sería una tienda en producción con dos
     * usuarias de PIN conocido. Esta comprobación no se puede borrar por descuido:
     * si el seed corre fuera de {@code dev}, la aplicación no arranca y el mensaje
     * dice exactamente qué pasó.
     */
    private void exigirPerfilDev() {
        List<String> perfiles = List.of(entorno.getActiveProfiles());
        if (!perfiles.contains(PERFIL_DEV)) {
            throw new IllegalStateException(
                    "SeedDesarrollo se ejecutó con los perfiles " + perfiles + ", sin '" + PERFIL_DEV
                            + "'. Este seed crea usuarias con PIN conocido y jamás debe correr fuera "
                            + "de desarrollo. Revisa la anotación @Profile(\"" + PERFIL_DEV + "\").");
        }
    }

    private Usuario usuario(String nombre, Rol rol, String pinHash, LocalDateTime ahora) {
        Usuario usuario = new Usuario();
        usuario.setNombre(nombre);
        usuario.setRol(rol);
        usuario.setPinHash(pinHash);
        usuario.setActivo(true);
        usuario.setFechaCreacion(ahora);
        return usuario;
    }

    private Marca marca(String nombre) {
        Marca marca = new Marca();
        marca.setNombre(nombre);
        return marca;
    }

    private Categoria categoria(String nombre) {
        Categoria categoria = new Categoria();
        categoria.setNombre(nombre);
        return categoria;
    }

    private Producto producto(String nombre, Marca marca, Categoria categoria, LocalDateTime ahora) {
        Producto producto = new Producto();
        producto.setNombre(nombre);
        producto.setMarca(marca);
        producto.setCategoria(categoria);
        producto.setFechaCreacion(ahora);
        return producto;
    }

    private Variante variante(Producto producto, String tono, String tamano,
                              long precioVenta, long costoPromedio, int stockMinimo,
                              LocalDateTime ahora) {
        Variante variante = new Variante();
        variante.setProducto(producto);
        variante.setTono(tono);
        variante.setTamano(tamano);
        variante.setPrecioVenta(precioVenta);
        variante.setCostoPromedio(costoPromedio);
        variante.setStockMinimo(stockMinimo);
        variante.setFechaCreacion(ahora);
        return variante;
    }
}
