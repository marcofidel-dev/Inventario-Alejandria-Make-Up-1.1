package com.alejandriamakeup.pos.configuracion;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.configuracion.dto.DatosTiendaDto;

/**
 * Los datos de la tienda.
 *
 * <p>Se lee entero de una vez —son cinco filas— y se escribe entero: no hay endpoint
 * para tocar una clave suelta. Guardar el bloque completo hace que la pantalla no
 * pueda dejar el NIT de una tienda con el nombre de otra si una petición se pierde a
 * la mitad.
 *
 * <p><strong>Una clave que falte vale cadena vacía, no revienta.</strong> La semilla
 * de V7 las crea las cinco, pero el recibo no puede depender de que una migración
 * haya corrido: si falta, el encabezado sale incompleto y la venta sigue teniendo su
 * comprobante.
 */
@Service
@Transactional(readOnly = true)
public class ServicioConfiguracion {

    private static final Logger log = LoggerFactory.getLogger(ServicioConfiguracion.class);

    static final String NOMBRE = "tienda.nombre";
    static final String NIT = "tienda.nit";
    static final String DIRECCION = "tienda.direccion";
    static final String TELEFONO = "tienda.telefono";
    static final String PIE_RECIBO = "tienda.pie_recibo";

    private final ConfiguracionRepository repositorio;

    public ServicioConfiguracion(ConfiguracionRepository repositorio) {
        this.repositorio = repositorio;
    }

    public DatosTiendaDto tienda() {
        Map<String, String> valores = new HashMap<>();
        for (Configuracion fila : repositorio.findAll()) {
            valores.put(fila.getClave(), fila.getValor());
        }
        return new DatosTiendaDto(
                valor(valores, NOMBRE),
                valor(valores, NIT),
                valor(valores, DIRECCION),
                valor(valores, TELEFONO),
                valor(valores, PIE_RECIBO));
    }

    @Transactional
    public DatosTiendaDto guardarTienda(DatosTiendaDto datos) {
        guardar(NOMBRE, datos.nombre());
        guardar(NIT, datos.nit());
        guardar(DIRECCION, datos.direccion());
        guardar(TELEFONO, datos.telefono());
        guardar(PIE_RECIBO, datos.pieRecibo());

        // Sin el valor: el nombre de la tienda no es secreto, pero un log que copie
        // cada campo guardado es un log que mañana copia el que no toca.
        log.info("Configuración de la tienda actualizada (nombre {})",
                datos.nombre() == null || datos.nombre().isBlank() ? "vacío" : "presente");
        return tienda();
    }

    private void guardar(String clave, String valor) {
        Configuracion fila = repositorio.findById(clave).orElseGet(() -> {
            Configuracion nueva = new Configuracion();
            nueva.setClave(clave);
            return nueva;
        });
        fila.setValor(valor == null ? "" : valor.strip());
        repositorio.save(fila);
    }

    private String valor(Map<String, String> valores, String clave) {
        String valor = valores.get(clave);
        return valor == null ? "" : valor;
    }
}
