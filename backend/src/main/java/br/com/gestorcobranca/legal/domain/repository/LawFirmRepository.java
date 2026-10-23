package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.LawFirm;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LawFirmRepository {

    LawFirm save(LawFirm firm);

    Optional<LawFirm> findById(UUID id);

    List<LawFirm> findAllByOrderByNameAsc();

    boolean existsByCnpj(String cnpj);
}
