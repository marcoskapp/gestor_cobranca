package br.com.gestorcobranca.identity.application.service.creditor;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.StepUpProofs;
import br.com.gestorcobranca.shared.security.StepUpPurpose;
import org.springframework.stereotype.Component;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Comprovantes da nova verificação do MFA (SEG-F07, decisão 1436): guardados só como recurso da transação corrente
 * (nada no banco nem entre requisições), desvinculados ao fim da transação (commit ou rollback) — a thread do pool nunca
 * leva um comprovante para a próxima requisição. Cada comprovante vale uma vez, para a mesma assessoria, pessoa, sessão,
 * finalidade e alvo.
 */
@Component
class CreditorStepUpProofs implements StepUpProofs {

    private static final Object KEY = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CurrentActor currentActor;

    CreditorStepUpProofs(CurrentActor currentActor) {
        this.currentActor = currentActor;
    }

    private record Issued(UUID tenantId, UUID personId, UUID sessionId, StepUpPurpose purpose, String subjectRef,
                          Instant verifiedAt) {
    }

    /** Emite o comprovante na transação corrente (chamado pela verificação do código, já conferido). */
    String issue(Actor actor, StepUpPurpose purpose, String subjectRef, Instant verifiedAt) {
        requireActiveTransaction();
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        proofs().put(proof, new Issued(actor.tenantId(), actor.id(), actor.sessionId(), purpose, subjectRef, verifiedAt));
        return proof;
    }

    @Override
    public Instant consume(String proof, StepUpPurpose purpose, String subjectRef) {
        if (proof == null || purpose == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive() || markedForRollback()) {
            throw invalid();
        }
        Holder holder = (Holder) TransactionSynchronizationManager.getResource(KEY);
        // a sincronização é suspensa numa transação aninhada (REQUIRES_NEW): o comprovante só vale na que o emitiu
        Issued issued = holder == null || !TransactionSynchronizationManager.getSynchronizations().contains(holder.cleanup())
                ? null : holder.proofs().get(proof);
        Actor actor = currentActor.get();
        if (issued == null || !actor.isCreditor() || !Objects.equals(issued.tenantId(), actor.tenantId())
                || !Objects.equals(issued.personId(), actor.id()) || !Objects.equals(issued.sessionId(), actor.sessionId())
                || issued.purpose() != purpose || !Objects.equals(issued.subjectRef(), subjectRef)) {
            throw invalid();
        }
        // uso único: sai da transação só depois de conferido (um chamador errado não gasta o comprovante de outro)
        holder.proofs().remove(proof);
        return issued.verifiedAt();
    }

    /**
     * Transação já marcada para desfazer não serve para a ação. Só a transação aberta por {@code @Transactional} expõe o
     * status aqui; a aberta por {@code TransactionTemplate} (o portal) desfaz por exceção, então não há o que conferir.
     */
    private static boolean markedForRollback() {
        try {
            return TransactionAspectSupport.currentTransactionStatus().isRollbackOnly();
        } catch (org.springframework.transaction.NoTransactionException e) {
            return false;
        }
    }

    private record Holder(TransactionSynchronization cleanup, Map<String, Issued> proofs) {
    }

    /** Comprovantes da transação corrente; o recurso é desvinculado ao fim dela (commit ou rollback). */
    private static Map<String, Issued> proofs() {
        Holder holder = (Holder) TransactionSynchronizationManager.getResource(KEY);
        if (holder != null) {
            if (!TransactionSynchronizationManager.getSynchronizations().contains(holder.cleanup())) {
                throw new IllegalStateException("A nova verificação do MFA não vale em transação aninhada");
            }
            return holder.proofs();
        }
        TransactionSynchronization cleanup = new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (TransactionSynchronizationManager.hasResource(KEY)) {
                    TransactionSynchronizationManager.unbindResource(KEY);
                }
            }
        };
        Holder created = new Holder(cleanup, new HashMap<>());
        TransactionSynchronizationManager.bindResource(KEY, created);
        TransactionSynchronizationManager.registerSynchronization(cleanup);
        return created.proofs();
    }

    private static void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("A nova verificação do MFA exige a transação da ação");
        }
    }

    private static BusinessRuleException invalid() {
        return new BusinessRuleException("creditor_step_up.proof_invalid",
                "A verificação do código do autenticador não vale para esta ação. Digite o código de novo.");
    }
}
