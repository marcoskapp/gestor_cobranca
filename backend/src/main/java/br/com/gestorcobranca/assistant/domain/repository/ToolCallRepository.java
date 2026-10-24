package br.com.gestorcobranca.assistant.domain.repository;

import br.com.gestorcobranca.assistant.domain.model.ToolCallRecord;

import java.util.List;
import java.util.UUID;

public interface ToolCallRepository {

    ToolCallRecord save(ToolCallRecord call);

    List<ToolCallRecord> findByTurnIdOrderBySequenceAsc(UUID turnId);
}
