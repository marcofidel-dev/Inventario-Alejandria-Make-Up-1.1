package com.alejandriamakeup.pos.clientes;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClienteRepository extends JpaRepository<Cliente, Long> {

    List<Cliente> findByNombreContainingIgnoreCase(String fragmento);

    Optional<Cliente> findByWhatsapp(String whatsapp);
}
