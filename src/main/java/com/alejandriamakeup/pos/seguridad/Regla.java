package com.alejandriamakeup.pos.seguridad;

/** Lo que una ruta exige. La ausencia de regla no es un caso: es una negación. */
public sealed interface Regla {

    /** Accesible sin sesión. */
    record Publico() implements Regla {
    }

    /** Basta con haber iniciado sesión, cualquier rol. */
    record Autenticado() implements Regla {
    }

    /** Requiere sesión y un permiso concreto. */
    record RequierePermiso(Permiso permiso) implements Regla {
    }
}
