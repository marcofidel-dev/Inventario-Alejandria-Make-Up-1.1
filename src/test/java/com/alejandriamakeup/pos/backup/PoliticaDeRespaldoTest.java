package com.alejandriamakeup.pos.backup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Los números de la política de respaldo, escritos a mano.
 *
 * <p>Existe por una lección concreta: el test del bloqueo de login usaba
 * {@code FALLOS_PARA_BLOQUEAR} como cota de su bucle, así que al subir la constante
 * de 5 a 50 el test se adaptó solo y siguió en verde — medía el mecanismo y era
 * ciego a la política. Lo comprobé rompiéndolo.
 *
 * <p>{@code BackupServiceTest} no tiene ese problema exacto, porque usa literales
 * alrededor de los umbrales (2 h y 20 h contra el corte de 12 h; 31 días y 30 días
 * menos una hora contra la retención de 30). Pero esos literales solo detectan
 * cambios que salgan del intervalo: pasar el umbral de 12 h a 15 h dejaría los dos
 * tests en verde. Aquí los números quedan clavados, de modo que cambiarlos sea una
 * decisión y no un efecto colateral.
 *
 * <p>Son tests de una línea a propósito. No prueban comportamiento — eso lo hace
 * {@code BackupServiceTest} — sino que la especificación y el código siguen diciendo
 * lo mismo.
 */
class PoliticaDeRespaldoTest {

    @Test
    void seRespaldaSiElUltimoTieneMasDeDoceHoras() {
        System.out.println("VERIFICACION política de antigüedad => "
                + BackupService.HORAS_ANTIGUEDAD_MAXIMA + " horas");
        assertThat(BackupService.HORAS_ANTIGUEDAD_MAXIMA).isEqualTo(12);
    }

    @Test
    void laRotacionConservaTreintaDias() {
        System.out.println("VERIFICACION política de retención => "
                + BackupService.DIAS_RETENCION + " días");
        assertThat(BackupService.DIAS_RETENCION).isEqualTo(30);
    }
}
