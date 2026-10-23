package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.quality.QualityForm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface QualityFormJpa extends JpaRepository<QualityForm, UUID> {

    List<QualityForm> findAllByOrderByActiveDescNameAsc();
}
