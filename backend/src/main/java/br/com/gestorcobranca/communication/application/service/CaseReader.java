package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Leitura de casos do collection em transação própria, com o mesmo ator. Os comandos de dono do
 * collection ({@code CaseWorkUseCases}) obtêm o lock do devedor e então carregam o caso; se o caso
 * já tivesse sido lido na transação deste módulo, o contexto de persistência devolveria a versão
 * lida antes do lock e uma alteração concorrente (ex.: recálculo de elegibilidade) faria o comando
 * falhar por lock otimista. Ler à parte mantém o comando sempre sobre o estado atual.
 */
@Component
class CaseReader {

    private final CaseUseCases cases;
    private final TenantExecution tenantExecution;
    private final CurrentActor currentActor;

    CaseReader(CaseUseCases cases, TenantExecution tenantExecution, CurrentActor currentActor) {
        this.cases = cases;
        this.tenantExecution = tenantExecution;
        this.currentActor = currentActor;
    }

    CaseView get(UUID caseId) {
        return tenantExecution.callAs(currentActor.get(), () -> cases.get(caseId));
    }

    /** Caso vinculado e ativo; encerrado → vazio. */
    Optional<CaseView> active(UUID caseId) {
        if (caseId == null) {
            return Optional.empty();
        }
        CaseView kase = get(caseId);
        return "CLOSED".equals(kase.status()) ? Optional.empty() : Optional.of(kase);
    }

    /** Único caso ativo do devedor no credor, se houver exatamente um. */
    Optional<CaseView> singleActive(UUID debtorId, UUID creditorId) {
        List<CaseView> active = tenantExecution.callAs(currentActor.get(), () -> cases.listByDebtor(debtorId)).stream()
                .filter(k -> k.creditorId().equals(creditorId) && !"CLOSED".equals(k.status()))
                .toList();
        return active.size() == 1 ? Optional.of(active.getFirst()) : Optional.empty();
    }
}
