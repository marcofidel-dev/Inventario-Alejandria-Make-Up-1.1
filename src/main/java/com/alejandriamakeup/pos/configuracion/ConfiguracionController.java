package com.alejandriamakeup.pos.configuracion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alejandriamakeup.pos.configuracion.dto.DatosTiendaDto;

import jakarta.validation.Valid;

/**
 * Los datos de la tienda que encabezan el recibo.
 *
 * <p>Solo la DUENA, por {@code Permiso.CONFIGURAR_TIENDA}: el NIT y la razón social
 * son la identidad fiscal del negocio, y cambiarlos afecta a todo comprobante que se
 * emita desde ese momento.
 */
@RestController
@RequestMapping("/api/v1/configuracion/tienda")
public class ConfiguracionController {

    private final ServicioConfiguracion servicio;

    public ConfiguracionController(ServicioConfiguracion servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public DatosTiendaDto tienda() {
        return servicio.tienda();
    }

    /** PUT y no PATCH: se guarda el bloque completo, nunca una clave suelta. */
    @PutMapping
    public DatosTiendaDto guardar(@Valid @RequestBody DatosTiendaDto datos) {
        return servicio.guardarTienda(datos);
    }
}
