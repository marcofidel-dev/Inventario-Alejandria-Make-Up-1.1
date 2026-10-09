package com.alejandriamakeup.pos.sistema;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alejandriamakeup.pos.backup.BackupService;
import com.alejandriamakeup.pos.backup.BackupService.ResultadoRespaldo;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/sistema")
public class SistemaController {

    private final BackupService backupService;
    private final ApagadorDeAplicacion apagador;

    public SistemaController(BackupService backupService, ApagadorDeAplicacion apagador) {
        this.backupService = backupService;
        this.apagador = apagador;
    }

    public record ApagadoPeticion(boolean forzar) {
    }

    public record ApagadoRespuesta(boolean exitoso, String motivo) {
    }

    /**
     * Respalda y apaga. Si el respaldo falla y la petición no viene con
     * {@code forzar}, no apaga: responde el motivo para que la pantalla
     * pregunte si cerrar de todas formas. Con {@code forzar} no reintenta el
     * respaldo —ya falló una vez, y reintentar de inmediato no cambia nada—,
     * va directo a apagar.
     */
    @PostMapping("/apagado")
    public ResponseEntity<ApagadoRespuesta> apagar(
            @RequestBody(required = false) ApagadoPeticion peticion, HttpServletRequest solicitud) {
        if (!esLocal(solicitud)) {
            throw ErrorDeAplicacion.accesoSoloLocal();
        }

        boolean forzar = peticion != null && peticion.forzar();
        if (!forzar) {
            ResultadoRespaldo resultado = backupService.respaldarConTimeout();
            if (!resultado.exitoso()) {
                return ResponseEntity.ok(new ApagadoRespuesta(false, resultado.motivo()));
            }
        }

        apagador.programarApagado();
        return ResponseEntity.ok(new ApagadoRespuesta(true, null));
    }

    /**
     * Solo {@code getRemoteAddr()}. Nunca {@code X-Forwarded-For} ni
     * {@code Forwarded}: la app escucha en {@code 0.0.0.0} para que el celular
     * vea el POS por wifi, y desde el celular no se puede apagar el sistema
     * con un encabezado inventado.
     */
    static boolean esLocal(HttpServletRequest solicitud) {
        try {
            return InetAddress.getByName(solicitud.getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
