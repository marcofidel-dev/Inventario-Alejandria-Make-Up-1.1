package com.alejandriamakeup.pos.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.alejandriamakeup.pos.PosApplication;

/**
 * Un respaldo que no se verifica no es un respaldo: se abre con una
 * conexión nueva, independiente de la app, y se confirma su contenido.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
class BackupServiceTest {

    @Autowired
    private BackupService backupService;

    @Autowired
    private DataSource dataSource;

    @Value("${app.paths.backups}")
    private String directorioBackupsProp;

    /**
     * El respaldo tiene que ser una copia fiel del esquema <em>vigente</em>, así
     * que se compara contra la lista de tablas de la base viva y no contra nombres
     * fijos. Antes esto solo exigía la tabla {@code Configuracion}, de V1: habría
     * pasado igual con un respaldo de un esquema viejo o con V2 sin aplicar. Y
     * como se compara contra lo que hay, no hay que tocar el test cada vez que se
     * agregue una migración.
     */
    @Test
    void elRespaldoCopiaElEsquemaVigenteCompletoYPasaIntegrityCheck() throws Exception {
        List<String> tablasEnVivo;
        try (Connection enVivo = dataSource.getConnection()) {
            tablasEnVivo = tablasDe(enVivo);
        }

        Path archivo = backupService.ejecutar();
        assertThat(archivo).exists();

        try (Connection conexion = DriverManager.getConnection("jdbc:sqlite:" + archivo);
             Statement statement = conexion.createStatement()) {

            List<String> tablasEnElBackup = tablasDe(conexion);
            System.out.println("VERIFICACION tablas en la base viva (" + tablasEnVivo.size() + ") => " + tablasEnVivo);
            System.out.println("VERIFICACION tablas en el backup    (" + tablasEnElBackup.size() + ") => " + tablasEnElBackup);
            assertThat(tablasEnElBackup).containsExactlyElementsOf(tablasEnVivo);
            // Que el test no pueda pasar contra una base vacía.
            assertThat(tablasEnVivo).contains("variante", "movimiento_inventario");

            // Que las tablas existan no prueba que el archivo sea restaurable:
            // integrity_check recorre páginas e índices del archivo completo.
            try (ResultSet integridad = statement.executeQuery("PRAGMA integrity_check")) {
                assertThat(integridad.next()).isTrue();
                String resultadoIntegridad = integridad.getString(1);
                System.out.println("VERIFICACION PRAGMA integrity_check sobre el backup => " + resultadoIntegridad);
                assertThat(resultadoIntegridad).isEqualToIgnoringCase("ok");
            }
        }
    }

    /** No cierra la conexión: la maneja quien la abrió. */
    private List<String> tablasDe(Connection conexion) throws Exception {
        try (Statement statement = conexion.createStatement();
             ResultSet filas = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
            List<String> nombres = new ArrayList<>();
            while (filas.next()) {
                nombres.add(filas.getString("name"));
            }
            return nombres;
        }
    }

    @Test
    void repetirElRespaldoElMismoDiaReemplazaElArchivoAnterior() throws Exception {
        Path primero = backupService.ejecutar();
        long primeraCreacion = Files.getLastModifiedTime(primero).toMillis();

        Path segundo = backupService.ejecutar();

        assertThat(segundo).isEqualTo(primero);
        assertThat(segundo).exists();
        assertThat(Files.getLastModifiedTime(segundo).toMillis()).isGreaterThanOrEqualTo(primeraCreacion);
    }

    /**
     * En {@code @TempDir} y no en el directorio compartido de respaldos: el test
     * creaba sus propios archivos, así que no dependía de residuo, pero convivía
     * con los que dejan los otros tests y dejaba los suyos ahí para siempre. Igual
     * que sus hermanos, se construye un {@link BackupService} apuntando a un
     * directorio limpio.
     */
    @Test
    void rotarRespaldosBorraLosMasViejosDe30DiasYConservaElResto(@TempDir Path directorioBackups) throws Exception {
        BackupService servicioAislado = new BackupService(dataSource, directorioBackups.toString());

        Path viejo35Dias = crearArchivoConAntiguedad(directorioBackups, "backup_2026-07-06.db", Duration.ofDays(35));
        Path viejo31Dias = crearArchivoConAntiguedad(directorioBackups, "backup_2026-07-10.db", Duration.ofDays(31));
        // Los límites se prueban con una hora de margen a cada lado, no sobre
        // el filo exacto: rotarRespaldos recalcula "hace 30 días" después de que
        // el setup fijó los mtime, así que el corte avanza unos microsegundos y
        // un archivo de exactamente 30 días cae de un lado o del otro según el
        // tick del reloj.
        Path casiEn30Dias = crearArchivoConAntiguedad(directorioBackups, "backup_2026-07-11.db",
                Duration.ofDays(30).plusHours(1));
        Path justoDentroDe30Dias = crearArchivoConAntiguedad(directorioBackups, "backup_2026-07-12.db",
                Duration.ofDays(30).minusHours(1));
        Path reciente10Dias = crearArchivoConAntiguedad(directorioBackups, "backup_2026-07-31.db", Duration.ofDays(10));
        Path deHoy = crearArchivoConAntiguedad(directorioBackups, "backup_2026-08-10.db", Duration.ZERO);
        Path archivoNoBackupViejo = crearArchivoConAntiguedad(directorioBackups, "notas.txt", Duration.ofDays(90));

        servicioAislado.rotarRespaldos();

        assertThat(viejo35Dias).doesNotExist();
        assertThat(viejo31Dias).doesNotExist();
        assertThat(casiEn30Dias).doesNotExist();
        assertThat(justoDentroDe30Dias).exists();
        assertThat(reciente10Dias).exists();
        assertThat(deHoy).exists();
        assertThat(archivoNoBackupViejo).exists();
    }

    @Test
    void respaldarSiEsNecesarioNoHaceNadaSiElUltimoTieneMenosDe12Horas(@TempDir Path directorioAislado) throws Exception {
        BackupService servicioAislado = new BackupService(dataSource, directorioAislado.toString());
        Path reciente = crearArchivoConAntiguedad(directorioAislado, "backup_reciente.db", Duration.ofHours(2));
        long mtimeAntes = Files.getLastModifiedTime(reciente).toMillis();

        servicioAislado.respaldarSiEsNecesario();

        try (Stream<Path> archivos = Files.list(directorioAislado)) {
            assertThat(archivos.count()).isEqualTo(1);
        }
        assertThat(Files.getLastModifiedTime(reciente).toMillis()).isEqualTo(mtimeAntes);
    }

    @Test
    void respaldarSiEsNecesarioRespaldaSiElUltimoTieneMasDe12Horas(@TempDir Path directorioAislado) throws Exception {
        BackupService servicioAislado = new BackupService(dataSource, directorioAislado.toString());
        crearArchivoConAntiguedad(directorioAislado, "backup_viejo.db", Duration.ofHours(20));

        servicioAislado.respaldarSiEsNecesario();

        Path nuevoDeHoy = directorioAislado.resolve("backup_" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".db");
        assertThat(nuevoDeHoy).exists();
    }

    @Test
    void respaldarSiEsNecesarioRespaldaSiNoHayNingunRespaldoPrevio(@TempDir Path directorioAislado) throws Exception {
        BackupService servicioAislado = new BackupService(dataSource, directorioAislado.toString());

        servicioAislado.respaldarSiEsNecesario();

        Path nuevoDeHoy = directorioAislado.resolve("backup_" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".db");
        assertThat(nuevoDeHoy).exists();
    }

    /**
     * Arranque, scheduler, cierre de caja y POST /backup pueden coincidir.
     * Sin serializar el borrado y el VACUUM, uno de los hilos revienta con
     * "output file already exists" o borra el archivo que el otro escribe.
     */
    @Test
    void respaldosConcurrentesNoSePisanEntreSi(@TempDir Path directorioAislado) throws Exception {
        BackupService servicioAislado = new BackupService(dataSource, directorioAislado.toString());
        int hilos = 4;
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Path>> resultados = new ArrayList<>();

        ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
        try {
            for (int i = 0; i < hilos; i++) {
                resultados.add(ejecutor.submit(() -> {
                    salida.await();
                    return servicioAislado.ejecutar();
                }));
            }
            salida.countDown();

            for (Future<Path> resultado : resultados) {
                Path archivo = resultado.get(30, TimeUnit.SECONDS);
                assertThat(archivo).exists();
            }
        } finally {
            ejecutor.shutdownNow();
        }

        Path esperado = directorioAislado.resolve(
                "backup_" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".db");
        try (Stream<Path> archivos = Files.list(directorioAislado)) {
            assertThat(archivos).containsExactly(esperado);
        }

        try (Connection conexion = DriverManager.getConnection("jdbc:sqlite:" + esperado);
             Statement statement = conexion.createStatement();
             ResultSet integridad = statement.executeQuery("PRAGMA integrity_check")) {
            assertThat(integridad.next()).isTrue();
            String resultadoIntegridad = integridad.getString(1);
            System.out.println("VERIFICACION integrity_check tras " + hilos
                    + " respaldos concurrentes => " + resultadoIntegridad);
            assertThat(resultadoIntegridad).isEqualToIgnoringCase("ok");
        }
    }

    /**
     * El fallo a mitad del VACUUM es el caso que motivó escribir-verificar-mover:
     * antes se borraba el destino primero, así que un VACUUM roto dejaba el día
     * sin ningún respaldo válido.
     */
    @Test
    void vacuumQueFallaAMitadDejaIntactoElRespaldoAnterior(@TempDir Path directorioAislado) throws Exception {
        Path anterior = crearRespaldoValido(directorioAislado);
        byte[] contenidoAntes = Files.readAllBytes(anterior);

        // El VACUUM alcanza a crear su archivo de salida y revienta después:
        // exactamente el "a mitad" que dejaba el destino ya borrado.
        BackupService servicioQueFalla = new BackupService(
                dataSourceQueEscribeYFalla(directorioAislado), directorioAislado.toString());

        assertThatThrownBy(servicioQueFalla::ejecutar)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("disk I/O error");

        assertThat(anterior).exists();
        assertThat(Files.readAllBytes(anterior)).isEqualTo(contenidoAntes);
        verificarQuePasaIntegrityCheck(anterior, "respaldo anterior tras VACUUM fallido");
        assertThat(temporalesEn(directorioAislado)).isEmpty();
    }

    /**
     * El VACUUM no lanza excepción pero produce un archivo ilegible. Sin el
     * integrity_check intermedio, esa basura reemplazaría al respaldo bueno.
     */
    @Test
    void respaldoQueNoPasaIntegrityCheckNoReemplazaAlAnterior(@TempDir Path directorioAislado) throws Exception {
        Path anterior = crearRespaldoValido(directorioAislado);
        byte[] contenidoAntes = Files.readAllBytes(anterior);

        BackupService servicioCorrupto = new BackupService(
                dataSourceQueEscribeBasura(directorioAislado), directorioAislado.toString());

        assertThatThrownBy(servicioCorrupto::ejecutar)
                .isInstanceOfAny(IllegalStateException.class, SQLException.class);

        assertThat(anterior).exists();
        assertThat(Files.readAllBytes(anterior)).isEqualTo(contenidoAntes);
        verificarQuePasaIntegrityCheck(anterior, "respaldo anterior tras VACUUM corrupto");
        assertThat(temporalesEn(directorioAislado)).isEmpty();
    }

    /** Un respaldo real y válido en el destino de hoy, listo para ser pisado. */
    private Path crearRespaldoValido(Path directorio) throws Exception {
        Path archivo = new BackupService(dataSource, directorio.toString()).ejecutar();
        verificarQuePasaIntegrityCheck(archivo, "respaldo previo recién creado");
        return archivo;
    }

    private void verificarQuePasaIntegrityCheck(Path archivo, String contexto) throws Exception {
        try (Connection conexion = DriverManager.getConnection("jdbc:sqlite:" + archivo);
             Statement statement = conexion.createStatement();
             ResultSet resultado = statement.executeQuery("PRAGMA integrity_check")) {
            assertThat(resultado.next()).isTrue();
            String veredicto = resultado.getString(1);
            System.out.println("VERIFICACION integrity_check (" + contexto + ") => " + veredicto);
            assertThat(veredicto).isEqualToIgnoringCase("ok");
        }
    }

    private List<Path> temporalesEn(Path directorio) throws Exception {
        try (Stream<Path> archivos = Files.list(directorio)) {
            return archivos.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList();
        }
    }

    /** DataSource cuyo VACUUM crea el archivo de salida y luego falla. */
    private DataSource dataSourceQueEscribeYFalla(Path directorio) throws Exception {
        return dataSourceConVacuum(directorio, destino -> {
            Files.write(destino, new byte[4096]);
            throw new SQLException("[SQLITE_IOERR] disk I/O error (simulado a mitad del VACUUM)");
        });
    }

    /** DataSource cuyo VACUUM "funciona" pero escribe un archivo ilegible. */
    private DataSource dataSourceQueEscribeBasura(Path directorio) throws Exception {
        return dataSourceConVacuum(directorio, destino ->
                Files.write(destino, "esto no es una base de datos SQLite".getBytes(StandardCharsets.UTF_8)));
    }

    private interface AccionVacuum {
        void ejecutar(Path destinoDelVacuum) throws Exception;
    }

    /**
     * Intercepta el {@code VACUUM INTO}, extrae de la sentencia la ruta de
     * salida y deja que el test decida qué escribir ahí. Así el doble no
     * necesita conocer el nombre del {@code .tmp} que elige el servicio.
     */
    private DataSource dataSourceConVacuum(Path directorio, AccionVacuum accion) throws Exception {
        DataSource falso = mock(DataSource.class);
        Connection conexion = mock(Connection.class);
        Statement statement = mock(Statement.class);

        when(falso.getConnection()).thenReturn(conexion);
        when(conexion.createStatement()).thenReturn(statement);
        when(statement.execute(startsWith("VACUUM INTO"))).thenAnswer(invocacion -> {
            String sql = invocacion.getArgument(0);
            Path destinoDelVacuum = Path.of(sql.substring(sql.indexOf('\'') + 1, sql.lastIndexOf('\'')));
            assertThat(destinoDelVacuum.getParent()).isEqualTo(directorio);
            assertThat(destinoDelVacuum.getFileName().toString()).endsWith(".tmp");
            accion.ejecutar(destinoDelVacuum);
            return false;
        });

        return falso;
    }

    private Path crearArchivoConAntiguedad(Path directorio, String nombre, Duration antiguedad) throws Exception {
        Path archivo = directorio.resolve(nombre);
        Files.deleteIfExists(archivo);
        Files.createFile(archivo);
        Files.setLastModifiedTime(archivo, FileTime.from(Instant.now().minus(antiguedad)));
        return archivo;
    }
}
