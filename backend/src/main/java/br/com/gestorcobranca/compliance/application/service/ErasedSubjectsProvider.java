package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.domain.model.DataSubjectRequest;
import br.com.gestorcobranca.compliance.domain.repository.DataSubjectRequestRepository;
import br.com.gestorcobranca.compliance.domain.repository.ErasedSubjectMatch;
import br.com.gestorcobranca.debt.application.port.spi.ErasedSubjects;
import br.com.gestorcobranca.shared.crypto.BlindIndex;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reconhece o titular eliminado a pedido pelo índice gravado na execução da eliminação (decisão 1471): recalcula o
 * índice com a finalidade do pedido a partir do documento que o debt recebeu — o índice do devedor nunca sai do debt.
 * O tenant é o do ator (o mesmo que a transação usa na RLS); sem ator, falha (a recusa nunca passa em silêncio).
 * <p>
 * Sem permissão própria: é chamada pelo debt como parte da criação do devedor e da carga, que conferem as deles. Não é
 * exposta em web, API pública, assistente nem ajuda ({@code ArchitectureTest}).
 */
@Component
class ErasedSubjectsProvider implements ErasedSubjects {

    private final DataSubjectRequestRepository requests;
    private final ErasedSubjectReturnRecorder recorder;
    private final BlindIndex blindIndex;
    private final CurrentActor currentActor;

    ErasedSubjectsProvider(DataSubjectRequestRepository requests, ErasedSubjectReturnRecorder recorder,
                           BlindIndex blindIndex, CurrentActor currentActor) {
        this.requests = requests;
        this.recorder = recorder;
        this.blindIndex = blindIndex;
        this.currentActor = currentActor;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<Document> erasedAmong(Map<Document, Integer> linesByDocument, ReturnAttempt attempt) {
        if (linesByDocument == null || linesByDocument.isEmpty()) {
            return Set.of();
        }
        if (linesByDocument.size() > MAX_DOCUMENTS) {
            throw new IllegalArgumentException("Consulta de titular eliminado acima de %d documentos".formatted(MAX_DOCUMENTS));
        }
        UUID tenantId = currentActor.tenantId();
        Map<String, Document> byHash = new HashMap<>();
        linesByDocument.keySet().forEach(document -> byHash.put(
                blindIndex.of(tenantId, DataSubjectRequest.DOCUMENT_HASH_PURPOSE, document.number()), document));
        List<ErasedSubjectMatch> matches = requests.findErasedSubjects(byHash.keySet());
        if (matches.isEmpty()) {
            return Set.of();
        }
        Set<Document> erased = new LinkedHashSet<>();
        Map<UUID, Integer> linesByRequest = new LinkedHashMap<>();
        for (ErasedSubjectMatch match : matches) {
            Document document = byHash.get(match.subjectHash());
            erased.add(document);
            linesByRequest.merge(match.requestId(), linesByDocument.getOrDefault(document, 1), Integer::sum);
        }
        // transação própria: a tentativa fica registrada mesmo que a criação do devedor seja desfeita
        recorder.record(linesByRequest, attempt);
        return erased;
    }
}
