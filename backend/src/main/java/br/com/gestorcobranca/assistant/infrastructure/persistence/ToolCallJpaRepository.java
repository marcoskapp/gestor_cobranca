package br.com.gestorcobranca.assistant.infrastructure.persistence;

import br.com.gestorcobranca.assistant.domain.model.ToolCallRecord;
import br.com.gestorcobranca.assistant.domain.repository.ToolCallRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ToolCallJpaRepository extends JpaRepository<ToolCallRecord, UUID>, ToolCallRepository {
}
