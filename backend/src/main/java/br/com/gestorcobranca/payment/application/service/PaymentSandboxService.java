package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.in.PaymentSandboxUseCases;
import br.com.gestorcobranca.payment.application.port.in.PixWebhookUseCases;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.PixTransaction;
import br.com.gestorcobranca.payment.application.port.out.PixSandbox;
import br.com.gestorcobranca.payment.domain.model.InstructionStatus;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentInstruction;
import br.com.gestorcobranca.payment.domain.model.PixAccount;
import br.com.gestorcobranca.payment.domain.model.PixProviderType;
import br.com.gestorcobranca.payment.domain.model.WebhookAuthMode;
import br.com.gestorcobranca.payment.domain.repository.PaymentInstructionRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import br.com.gestorcobranca.payment.domain.repository.PixAccountRepository;
import br.com.gestorcobranca.payment.domain.service.WebhookAuthentication;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Simulação do PSP (somente com {@code app.payment.sandbox.enabled=true}, recusado em produção).
 * A notificação montada percorre o mesmo caminho de produção: autenticação HMAC, recibo, janela de
 * replay, consulta ao PSP (simulado) e confirmação.
 */
@Service
class PaymentSandboxService implements PaymentSandboxUseCases {

    private final ObjectProvider<PixSandbox> sandbox;
    private final PaymentInstructionRepository instructions;
    private final PaymentRepository payments;
    private final PixAccountRepository accounts;
    private final PixCredentialCodec credentials;
    private final PixWebhookUseCases webhook;
    private final AccessScope accessScope;
    private final PaymentProperties properties;
    private final JsonCodec json;
    private final Clock clock;

    PaymentSandboxService(ObjectProvider<PixSandbox> sandbox, PaymentInstructionRepository instructions,
                          PaymentRepository payments, PixAccountRepository accounts, PixCredentialCodec credentials,
                          PixWebhookUseCases webhook, AccessScope accessScope, PaymentProperties properties,
                          JsonCodec json, Clock clock) {
        this.sandbox = sandbox;
        this.instructions = instructions;
        this.payments = payments;
        this.accounts = accounts;
        this.credentials = credentials;
        this.webhook = webhook;
        this.accessScope = accessScope;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public SimulatedNotification simulatePixPayment(UUID instructionId, BigDecimal amount, boolean deliver) {
        PixSandbox psp = requireSandbox();
        Prepared prepared = prepare(() -> {
            PaymentInstruction instruction = instructions.findById(instructionId)
                    .orElseThrow(() -> new ResourceNotFoundException("payment_instruction", instructionId));
            accessScope.requireCreditor(instruction.getCreditorId());
            Guard.requireState(instruction.getStatus() == InstructionStatus.ACTIVE || instruction.getStatus()
                    == InstructionStatus.PAID, "sandbox.instruction_not_active", "Instrução não está ativa");
            PixAccount account = sandboxAccount(instruction.getPixAccountId());
            PixTransaction tx = psp.pay(account.getId(), instruction.getTxid(),
                    amount == null ? instruction.getAmount().amount() : amount);
            return new Prepared(account, tx);
        });
        return notify(prepared, deliver);
    }

    @Override
    @Transactional(readOnly = true)
    public SimulatedNotification simulatePixRefund(String endToEndId, BigDecimal amount, boolean deliver) {
        PixSandbox psp = requireSandbox();
        Prepared prepared = prepare(() -> {
            Payment payment = payments.findByProviderTransactionId(endToEndId)
                    .orElseThrow(() -> new ResourceNotFoundException("payment", endToEndId));
            accessScope.requireCreditor(payment.getCreditorId());
            PaymentInstruction instruction = instructions.findById(payment.getInstructionId()).orElseThrow();
            PixAccount account = sandboxAccount(instruction.getPixAccountId());
            return new Prepared(account, psp.refund(account.getId(), endToEndId,
                    amount == null ? payment.getAmount().amount() : amount));
        });
        return notify(prepared, deliver);
    }

    private record Prepared(PixAccount account, PixTransaction tx) {
    }

    private Prepared prepare(java.util.function.Supplier<Prepared> action) {
        accessScope.requirePermission(Permission.INTEGRATION_MANAGE);
        return action.get();
    }

    private SimulatedNotification notify(Prepared prepared, boolean deliver) {
        PixTransaction tx = prepared.tx();
        Map<String, Object> pix = new LinkedHashMap<>();
        pix.put("endToEndId", tx.endToEndId());
        pix.put("txid", tx.txid());
        pix.put("valor", tx.amount().toPlainString());
        pix.put("chave", prepared.account().getPixKey());
        pix.put("horario", Instant.now(clock).atOffset(ZoneOffset.UTC).toString());
        if (!tx.refunds().isEmpty()) {
            pix.put("devolucoes", tx.refunds().stream().map(r -> Map.of("id", r.id(), "rtrId", r.returnId(),
                    "valor", r.amount().toPlainString(), "status", r.status())).toList());
        }
        String body = json.toJson(Map.of("pix", List.of(pix)));
        String secret = credentials.decode(prepared.account().getCredentials()).get(PixCredentialCodec.WEBHOOK_SECRET);
        String signature = secret == null ? null : WebhookAuthentication.hmacHeader(secret,
                body.getBytes(StandardCharsets.UTF_8));
        PixWebhookUseCases.Outcome outcome = deliver
                ? webhook.receive(prepared.account().getRoutingKey(), signature, null, body.getBytes(StandardCharsets.UTF_8),
                "127.0.0.1")
                : null;
        return new SimulatedNotification(tx.endToEndId(), tx.txid(), prepared.account().getRoutingKey(), body, signature,
                outcome);
    }

    private PixAccount sandboxAccount(UUID accountId) {
        PixAccount account = accounts.findById(accountId).orElseThrow();
        if (account.getProvider() != PixProviderType.SANDBOX || account.getWebhookAuth() != WebhookAuthMode.HMAC) {
            throw new BusinessRuleException("sandbox.account_invalid", "Simulação exige conta SANDBOX com webhook HMAC");
        }
        return account;
    }

    private PixSandbox requireSandbox() {
        PixSandbox psp = properties.sandbox().enabled() ? sandbox.getIfAvailable() : null;
        if (psp == null) {
            throw new BusinessRuleException("sandbox.disabled", "Simulador de PSP desabilitado neste ambiente");
        }
        return psp;
    }
}
