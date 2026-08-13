package com.alejandriamakeup.pos.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Respaldo con {@code VACUUM INTO}. Nunca copiar el .db en caliente: con WAL
 * activo la copia puede salir corrupta.
 *
 * <p>El PC de la tienda se apaga al cerrar, así que un cron a hora fija no
 * sirve como mecanismo principal: si la hora cae con el equipo apagado, el
 * respaldo simplemente no corre y no queda ningún error visible. El
 * disparador principal es {@link #respaldarSiEsNecesario()}, invocado al
 * arrancar la app y (más adelante) desde el cierre de caja. El
 * {@code @Scheduled} de esta clase es solo una red adicional para el caso
 * atípico de un equipo que queda encendido muchas horas sin reiniciar.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ISO_LOCAL_DATE;
    // Visibles al test para que pueda fijar la política. Los tests de comportamiento
    // usan literales alrededor del umbral, que los deja aproximadamente sensibles a un
    // cambio; PoliticaDeRespaldoTest exige los números exactos.
    static final int DIAS_RETENCION = 30;
    static final int HORAS_ANTIGUEDAD_MAXIMA = 12;
    private static final String PREFIJO = "backup_";
    private static final String SUFIJO = ".db";
    private static final String SUFIJO_TEMPORAL = ".tmp";

    private final DataSource dataSource;
    private final Path directorioBackups;

    /**
     * Dos respaldos simultáneos apuntan al mismo {@code .tmp}, y
     * {@code VACUUM INTO} falla si su archivo de salida ya existe. El pool de
     * Hikari es de una sola conexión, pero eso no alcanza: el borrado del
     * {@code .tmp} ocurre antes de pedirla, de modo que dos hilos pueden
     * borrar, esperar turno y luego chocar — o peor, uno borra el {@code .tmp}
     * que el otro está escribiendo. Los llamadores concurrentes son reales:
     * arranque, scheduler, cierre de caja y POST /backup.
     */
    private final Object cerrojo = new Object();

    public BackupService(DataSource dataSource, @Value("${app.paths.backups}") String directorioBackups) {
        this.dataSource = dataSource;
        this.directorioBackups = Path.of(directorioBackups);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        respaldarSiEsNecesario();
    }

    /**
     * Red adicional: si el equipo queda encendido muchas horas seguidas sin
     * reiniciar ni cerrar caja, esto vuelve a evaluar la antigüedad del
     * último respaldo. No es el mecanismo principal.
     *
     * <p>El {@code initialDelay} evita que la primera corrida pise el
     * respaldo de arranque: sin él {@code fixedRate} dispara de inmediato y
     * los dos hilos compiten por el mismo archivo destino.
     */
    @Scheduled(fixedRate = 6, initialDelay = 6, timeUnit = TimeUnit.HOURS)
    public void redAdicionalProgramada() {
        respaldarSiEsNecesario();
    }

    /**
     * Respalda solo si el último respaldo tiene más de {@value #HORAS_ANTIGUEDAD_MAXIMA}
     * horas (o no existe ninguno). Se usa al arrancar la app y desde el
     * scheduler de red adicional.
     */
    public void respaldarSiEsNecesario() {
        synchronized (cerrojo) {
            if (fechaUltimoRespaldo().map(this::esViejo).orElse(true)) {
                try {
                    ejecutar();
                    rotarRespaldos();
                } catch (Exception e) {
                    log.error("Falló el respaldo automático", e);
                }
            }
        }
    }

    /**
     * Genera {@code backups/backup_YYYY-MM-DD.db} de inmediato, sin evaluar
     * antigüedad. Es el respaldo "a la fuerza": lo usan el disparo manual
     * (POST /backup) y, más adelante, el cierre de caja. Si ya existe uno de
     * hoy, se reemplaza.
     *
     * <p>Escribir-verificar-mover. Borrar el destino antes del
     * {@code VACUUM INTO} sería destructivo: si el VACUUM falla a mitad ya no
     * queda ningún respaldo válido de ese día, y entre arranque, cierre de
     * caja y scheduler el mismo archivo se reescribe varias veces al día. Así
     * que el VACUUM va a un {@code .tmp} de scratch, se verifica su
     * integridad, y solo un archivo ya probado reemplaza al anterior. El
     * respaldo previo sobrevive intacto a cualquier fallo del camino.
     */
    public Path ejecutar() throws SQLException {
        synchronized (cerrojo) {
            String nombreArchivo = PREFIJO + LocalDate.now().format(FORMATO_FECHA) + SUFIJO;
            Path destino = directorioBackups.resolve(nombreArchivo);
            Path temporal = directorioBackups.resolve(nombreArchivo + SUFIJO_TEMPORAL);

            try {
                // El .tmp sí se puede borrar de entrada: es scratch, no un
                // respaldo. Un .tmp huérfano solo puede venir de un fallo
                // anterior o de un corte de luz a mitad del VACUUM.
                Files.deleteIfExists(temporal);

                String sql = "VACUUM INTO '" + temporal.toString().replace("'", "''") + "'";
                try (Connection conexion = dataSource.getConnection();
                     Statement statement = conexion.createStatement()) {
                    statement.execute(sql);
                }

                verificarIntegridad(temporal);

                // Mismo directorio, mismo volumen: el ATOMIC_MOVE es un
                // rename, no una copia. Nadie puede observar un destino a
                // medio escribir.
                Files.move(temporal, destino,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo generar el respaldo en " + destino, e);
            } finally {
                limpiarTemporal(temporal);
            }

            log.info("Respaldo generado en {}", destino);
            return destino;
        }
    }

    /**
     * Que el {@code VACUUM INTO} no haya lanzado excepción no prueba que el
     * archivo sea restaurable. {@code integrity_check} recorre páginas e
     * índices, y se hace sobre una conexión propia al {@code .tmp} — nunca
     * sobre el {@link DataSource} de la app, que apunta a la base viva. La
     * conexión se cierra antes del {@code move}: en Windows un handle abierto
     * bloquearía el rename.
     */
    private void verificarIntegridad(Path archivo) throws SQLException {
        String veredicto;
        try (Connection conexion = DriverManager.getConnection("jdbc:sqlite:" + archivo);
             Statement statement = conexion.createStatement();
             ResultSet resultado = statement.executeQuery("PRAGMA integrity_check")) {
            veredicto = resultado.next() ? resultado.getString(1) : "sin resultado";
        }

        if (!"ok".equalsIgnoreCase(veredicto)) {
            throw new IllegalStateException(
                    "El respaldo recién generado no pasó integrity_check (" + veredicto
                            + "); se conserva el respaldo anterior: " + archivo);
        }
    }

    private void limpiarTemporal(Path temporal) {
        try {
            Files.deleteIfExists(temporal);
        } catch (IOException e) {
            log.warn("Quedó un temporal de respaldo sin borrar: {}", temporal, e);
        }
    }

    public void rotarRespaldos() {
        Instant limite = Instant.now().minus(DIAS_RETENCION, ChronoUnit.DAYS);
        try (Stream<Path> archivos = Files.list(directorioBackups)) {
            archivos.filter(BackupService::esArchivoDeBackup)
                    .filter(p -> esAnterior(p, limite))
                    .forEach(this::eliminar);
        } catch (IOException e) {
            log.error("No se pudo rotar los respaldos", e);
        }
    }

    private boolean esViejo(Instant fecha) {
        return fecha.isBefore(Instant.now().minus(HORAS_ANTIGUEDAD_MAXIMA, ChronoUnit.HOURS));
    }

    private Optional<Instant> fechaUltimoRespaldo() {
        try (Stream<Path> archivos = Files.list(directorioBackups)) {
            return archivos.filter(BackupService::esArchivoDeBackup)
                    .map(this::fechaModificacion)
                    .flatMap(Optional::stream)
                    .max(Comparator.naturalOrder());
        } catch (IOException e) {
            log.warn("No se pudo leer el directorio de respaldos", e);
            return Optional.empty();
        }
    }

    private Optional<Instant> fechaModificacion(Path archivo) {
        try {
            return Optional.of(Files.getLastModifiedTime(archivo).toInstant());
        } catch (IOException e) {
            log.warn("No se pudo leer la fecha de {}", archivo, e);
            return Optional.empty();
        }
    }

    private static boolean esArchivoDeBackup(Path archivo) {
        String nombre = archivo.getFileName().toString();
        return nombre.startsWith(PREFIJO) && nombre.endsWith(SUFIJO);
    }

    private boolean esAnterior(Path archivo, Instant limite) {
        try {
            return Files.getLastModifiedTime(archivo).toInstant().isBefore(limite);
        } catch (IOException e) {
            log.warn("No se pudo leer la fecha de {}", archivo, e);
            return false;
        }
    }

    private void eliminar(Path archivo) {
        try {
            Files.delete(archivo);
            log.info("Respaldo eliminado por rotación (>{} días): {}", DIAS_RETENCION, archivo);
        } catch (IOException e) {
            log.warn("No se pudo eliminar el respaldo {}", archivo, e);
        }
    }
}
