package com.alejandriamakeup.pos.compras;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CompraItemRepository extends JpaRepository<CompraItem, Long> {

    List<CompraItem> findByCompraId(Long compraId);

    List<CompraItem> findByVarianteId(Long varianteId);
}
