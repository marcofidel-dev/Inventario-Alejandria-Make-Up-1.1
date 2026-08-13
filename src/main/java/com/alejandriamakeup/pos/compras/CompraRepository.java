package com.alejandriamakeup.pos.compras;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CompraRepository extends JpaRepository<Compra, Long> {

    Optional<Compra> findByConsecutivo(String consecutivo);

    List<Compra> findByProveedorId(Long proveedorId);

    List<Compra> findByEstado(EstadoCompra estado);

    List<Compra> findByFechaBetweenOrderByFechaAsc(LocalDateTime desde, LocalDateTime hasta);
}
