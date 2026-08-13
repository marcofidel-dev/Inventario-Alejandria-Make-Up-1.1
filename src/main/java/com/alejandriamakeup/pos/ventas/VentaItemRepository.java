package com.alejandriamakeup.pos.ventas;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Los items son inmutables: solo lectura y append. */
public interface VentaItemRepository extends JpaRepository<VentaItem, Long> {

    List<VentaItem> findByVentaId(Long ventaId);

    List<VentaItem> findByVarianteId(Long varianteId);
}
