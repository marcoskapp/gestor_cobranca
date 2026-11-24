package br.com.gestorcobranca.identity.application.service.creditor;

import br.com.gestorcobranca.identity.application.IdentityProperties;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorStepUp;
import br.com.gestorcobranca.identity.application.port.out.IdentityMailer;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorUser;
import br.com.gestorcobranca.identity.domain.repository.CreditorAccountRepository;
import br.com.gestorcobranca.identity.domain.service.Totp;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Nova verificação do MFA da pessoa do credor na transação da ação (ADR 0011, RS-23/RS-24). O acerto grava o passo
 * usado junto da ação (se a ação desfaz, o código pode ser repetido no mesmo passo); o código inválido é gravado numa
 * transação própria, para o bloqueio valer mesmo com a ação desfeita — no limite, a conta é bloqueada e as sessões
 * revogadas. Código certo já usado é recusado sem contar (não é adivinhação).
 */
@Service
class CreditorStepUpService implements CreditorStepUp {

    private final CreditorAccountRepository accounts;
    private final StepUpFailures failures;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final Clock clock;

    CreditorStepUpService(CreditorAccountRepository accounts, StepUpFailures failures, CurrentActor currentActor,
                          AccessLogger accessLogger, Clock clock) {
        this.accounts = accounts;
        this.failures = failures;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Instant verify(String code, String purpose) {
        Actor actor = currentActor.get();
        if (!actor.isCreditor() || actor.sessionId() == null) {
            throw new ScopeAccessDeniedException();
        }
        Instant now = Instant.now(clock);
        // leitura sem trava: a falha é gravada em outra transação, que precisa travar a mesma linha (com a trava aqui,
        // as duas esperariam uma pela outra); a trava só vem no acerto, para gravar o passo usado
        CreditorUser user = accounts.findUser(actor.id()).orElseThrow(ScopeAccessDeniedException::new);
        OptionalLong step = user.canAuthenticate(now) && code != null && code.matches("\\d{6}")
                ? Totp.verify(user.getMfaSecret(), code, now) : OptionalLong.empty();
        if (step.isEmpty()) {
            boolean locked = failures.record(actor, purpose, "invalid_code");
            throw new BusinessRuleException("creditor_step_up.invalid", locked
                    ? "Acesso bloqueado por tentativas inválidas. Entre de novo mais tarde."
                    : "Código do autenticador inválido. Digite o código atual do aplicativo.");
        }
        user = accounts.lockAndRefresh(user);
        if (!user.acceptMfaStep(step.getAsLong())) {
            // código certo já usado (replay ou dois cliques): recusado e registrado, sem contar como adivinhação — e
            // sem a transação de falhas, que travaria a linha já travada aqui
            accessLogger.log(AccessEvent.of(AccessEventType.MFA_FAILURE, actor,
                    Map.of("purpose", purpose, "stepUp", "true", "reason", "code_reused")));
            throw new BusinessRuleException("creditor_step_up.invalid",
                    "Este código já foi usado. Espere o próximo código do aplicativo.");
        }
        user.registerSuccessfulVerification();
        accounts.saveUser(user);
        accessLogger.log(AccessEvent.of(AccessEventType.MFA_SUCCESS, actor, Map.of("purpose", purpose, "stepUp", "true")));
        return now;
    }

    /** Falhas da nova verificação em transação própria (sobrevivem ao rollback da ação). */
    @Component
    static class StepUpFailures {

        private final CreditorAccountRepository accounts;
        private final CreditorSessions sessions;
        private final IdentityProperties identityProperties;
        private final IdentityMailer mailer;
        private final AccessLogger accessLogger;
        private final Clock clock;

        StepUpFailures(CreditorAccountRepository accounts, CreditorSessions sessions, IdentityProperties identityProperties,
                       IdentityMailer mailer, AccessLogger accessLogger, Clock clock) {
            this.accounts = accounts;
            this.sessions = sessions;
            this.identityProperties = identityProperties;
            this.mailer = mailer;
            this.accessLogger = accessLogger;
            this.clock = clock;
        }

        /** @return true se a conta foi bloqueada (e as sessões revogadas) nesta falha */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        boolean record(Actor actor, String purpose, String reason) {
            Instant now = Instant.now(clock);
            CreditorUser user = accounts.findUserForUpdate(actor.id()).orElse(null);
            if (user == null) {
                return false;
            }
            boolean locked = user.registerFailure(now, identityProperties.lockout().toPolicy());
            accounts.saveUser(user);
            if (locked) {
                sessions.revokeAll(user.getId(), "step_up_lockout");
                mailer.sendSecurityAlert(user.getEmail(), user.getName(), "Acesso ao portal do credor bloqueado",
                        "Seu acesso ao portal do credor foi bloqueado após códigos do autenticador inválidos numa "
                                + "confirmação. Se não foi você, avise a assessoria.");
            }
            accessLogger.log(AccessEvent.of(locked ? AccessEventType.LOGIN_BLOCKED : AccessEventType.MFA_FAILURE, actor,
                    Map.of("purpose", purpose, "stepUp", "true", "reason", reason)));
            return locked;
        }
    }
}
