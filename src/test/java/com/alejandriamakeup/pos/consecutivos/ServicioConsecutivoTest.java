package com.alejandriamakeup.pos.consecutivos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.alejandriamakeup.pos.PosApplication;

/**
 * Los consecutivos no pueden repetirse ni dejar huecos: un recibo duplicado o un
 * número faltante es un problema contable.
 *
 * <p>Sobre el alcance del test concurrente: con {@code maximum-pool-size: 1} los
 * hilos se serializan al pedir conexión, así que esto demuestra que la secuencia
 * no repite ni salta bajo contención — no que aguante escrituras paralelas de
 * verdad contra la base. Estas últimas no pueden ocurrir en esta app: el pool de
 * una sola conexión es parte del diseño, no una limitación del test.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
class ServicioConsecutivoTest {

    private static final int HILOS = 8;

    @Autowired
    private ServicioConsecutivo servicioConsecutivo;

    @Autowired
    private ConsecutivoRepository consecutivoRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void entregaElFormatoConPrefijoYSeisDigitos() {
        String consecutivo = enTransaccion(() -> servicioConsecutivo.siguiente(TipoConsecutivo.VENTA));

        System.out.println("VERIFICACION consecutivo entregado => " + consecutivo);
        assertThat(consecutivo).matches("V-\\d{6}");
    }

    @Test
    void cadaSerieLlevaSuPropioContador() {
        String venta = enTransaccion(() -> servicioConsecutivo.siguiente(TipoConsecutivo.VENTA));
        String compra = enTransaccion(() -> servicioConsecutivo.siguiente(TipoConsecutivo.COMPRA));
        String sesion = enTransaccion(() -> servicioConsecutivo.siguiente(TipoConsecutivo.SESION_CAJA));

        System.out.println("VERIFICACION series independientes => " + venta + " / " + compra + " / " + sesion);
        assertThat(venta).startsWith("V-");
        assertThat(compra).startsWith("C-");
        assertThat(sesion).startsWith("S-");
    }

    @Test
    void llamarloFueraDeUnaTransaccionEsUnError() {
        assertThatThrownBy(() -> servicioConsecutivo.siguiente(TipoConsecutivo.VENTA))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void bajoContencionNoRepiteNiDejaHuecos() throws Exception {
        long inicio = ultimoNumeroDe(TipoConsecutivo.COMPRA);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<String>> resultados = new ArrayList<>();

        ExecutorService ejecutor = Executors.newFixedThreadPool(HILOS);
        try {
            for (int i = 0; i < HILOS; i++) {
                resultados.add(ejecutor.submit(() -> {
                    salida.await();
                    return enTransaccion(() -> servicioConsecutivo.siguiente(TipoConsecutivo.COMPRA));
                }));
            }
            salida.countDown();

            List<Long> numeros = new ArrayList<>();
            for (Future<String> resultado : resultados) {
                numeros.add(numeroDe(resultado.get(30, TimeUnit.SECONDS)));
            }

            List<Long> esperados = new ArrayList<>();
            for (long n = inicio + 1; n <= inicio + HILOS; n++) {
                esperados.add(n);
            }

            System.out.println("VERIFICACION " + HILOS + " consecutivos concurrentes desde " + inicio
                    + " => " + numeros.stream().sorted().toList());
            assertThat(numeros).doesNotHaveDuplicates();
            assertThat(numeros).containsExactlyInAnyOrderElementsOf(esperados);
            assertThat(ultimoNumeroDe(TipoConsecutivo.COMPRA)).isEqualTo(inicio + HILOS);
        } finally {
            ejecutor.shutdownNow();
        }
    }

    @Test
    void siLaTransaccionHaceRollbackElConsecutivoNoQuedaQuemado() {
        long antes = ultimoNumeroDe(TipoConsecutivo.SESION_CAJA);

        assertThatThrownBy(() -> enTransaccion(() -> {
            servicioConsecutivo.siguiente(TipoConsecutivo.SESION_CAJA);
            throw new IllegalStateException("el documento falló después de pedir el consecutivo");
        })).isInstanceOf(IllegalStateException.class);

        long despues = ultimoNumeroDe(TipoConsecutivo.SESION_CAJA);
        System.out.println("VERIFICACION contador tras rollback => antes=" + antes + " despues=" + despues);
        assertThat(despues).isEqualTo(antes);
    }

    /** Extrae el número de un consecutivo formateado: {@code "C-000007"} → 7. */
    private long numeroDe(String consecutivo) {
        return Long.parseLong(consecutivo.substring(consecutivo.indexOf('-') + 1));
    }

    private long ultimoNumeroDe(TipoConsecutivo tipo) {
        return enTransaccion(() -> consecutivoRepository.findById(tipo).orElseThrow().getUltimoNumero());
    }

    private <T> T enTransaccion(Callable<T> accion) {
        TransactionTemplate plantilla = new TransactionTemplate(transactionManager);
        return plantilla.execute(estado -> {
            try {
                return accion.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
