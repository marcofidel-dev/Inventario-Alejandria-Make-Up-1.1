package com.alejandriamakeup.pos.compras.dto;

import com.alejandriamakeup.pos.compras.Proveedor;

public record ProveedorDto(
        Long id,
        String nombre,
        String nit,
        String telefono,
        String contacto,
        String notas,
        boolean activo) {

    public static ProveedorDto de(Proveedor proveedor) {
        return new ProveedorDto(
                proveedor.getId(),
                proveedor.getNombre(),
                proveedor.getNit(),
                proveedor.getTelefono(),
                proveedor.getContacto(),
                proveedor.getNotas(),
                proveedor.isActivo());
    }
}
