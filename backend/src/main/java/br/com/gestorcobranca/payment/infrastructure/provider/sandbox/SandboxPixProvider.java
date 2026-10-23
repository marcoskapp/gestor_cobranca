package br.com.gestorcobranca.payment.infrastructure.provider.sandbox;

import br.com.gestorcobranca.payment.application.port.out.PixProvider;
import br.com.gestorcobranca.payment.application.port.out.PixSandbox;
import br.com.gestorcobranca.payment.domain.model.PixProviderType;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PSP simulado para ambiente local e testes ({@code app.payment.sandbox.enabled=true}; recusado no
 * perfil {@code prod} por {@code PaymentSandboxGuard}). Mantém cobranças e Pix recebidos em memória (por
 * instância), sem acesso à rede. O "copia e cola" devolvido é um BR Code fictício, não pagável.
 * Marcador no {@code txid}: cobranças cuja descrição contém {@code [sandbox:reject]} são recusadas.
 */
@Component
@ConditionalOnProperty(prefix = "app.payment.sandbox", name = "enabled", havingValue = "true")
class SandboxPixProvider implements PixProvider, PixSandbox {

    static final String REJECT = "[sandbox:reject]";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final Map<String, Charge> charges = new ConcurrentHashMap<>();
    private final Map<String, PixTransaction> received = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    SandboxPixProvider(MeterRegistry meterRegistry, Clock clock) {
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    private record Charge(UUID accountId, String txid, BigDecimal amount, boolean active) {
    }

    @Override
    public PixProviderType type() {
        return PixProviderType.SANDBOX;
    }

    @Override
    public ChargeResult createCharge(PixConnection connection, ChargeRequest request) {
        if (request.description() != null && request.description().contains(REJECT)) {
            count("create_charge", "rejected");
            throw new ProviderException("SANDBOX_REJECTED", "Recusa simulada", false, null);
        }
        charges.put(request.txid(), new Charge(connection.accountId(), request.txid(), request.amount(), true));
        count("create_charge", "success");
        String copyPaste = "00020101021226830014br.gov.bcb.pix2561sandbox.local/qr/v2/" + request.txid()
                + "5204000053039865802BR5915GESTOR SANDBOX6009SAO PAULO62070503***6304ABCD";
        return new ChargeResult(request.txid(), 0, "sandbox.local/qr/v2/" + request.txid(), copyPaste,
                request.due() ? "ATIVA" : "ATIVA");
    }

    @Override
    public void cancelCharge(PixConnection connection, String txid, boolean due) {
        charges.computeIfPresent(txid, (k, c) -> new Charge(c.accountId(), c.txid(), c.amount(), false));
        count("cancel_charge", "success");
    }

    @Override
    public Optional<PixTransaction> findPix(PixConnection connection, String endToEndId) {
        count("find_pix", "success");
        return Optional.ofNullable(received.get(endToEndId)).filter(tx -> charges.containsKey(tx.txid())
                && charges.get(tx.txid()).accountId().equals(connection.accountId()));
    }

    @Override
    public void configureWebhook(PixConnection connection, String webhookUrl) {
        count("configure_webhook", "success");
    }

    @Override
    public PixTransaction pay(UUID accountId, String txid, BigDecimal amount) {
        Charge charge = charges.get(txid);
        if (charge == null || !charge.accountId().equals(accountId)) {
            throw new BusinessRuleException("sandbox.charge_not_found", "Cobrança inexistente no PSP simulado");
        }
        PixTransaction tx = new PixTransaction(newEndToEndId(), txid, amount, Instant.now(clock), List.of());
        received.put(tx.endToEndId(), tx);
        return tx;
    }

    @Override
    public PixTransaction refund(UUID accountId, String endToEndId, BigDecimal amount) {
        PixTransaction tx = received.get(endToEndId);
        if (tx == null || !charges.get(tx.txid()).accountId().equals(accountId)) {
            throw new BusinessRuleException("sandbox.pix_not_found", "Pix inexistente no PSP simulado");
        }
        List<Refund> refunds = new ArrayList<>(tx.refunds());
        refunds.add(new Refund("D" + (refunds.size() + 1), newEndToEndId().replaceFirst("^E", "D"), amount, "DEVOLVIDO",
                Instant.now(clock)));
        PixTransaction updated = new PixTransaction(tx.endToEndId(), tx.txid(), tx.amount(), tx.paidAt(), refunds);
        received.put(endToEndId, updated);
        return updated;
    }

    private String newEndToEndId() {
        StringBuilder sb = new StringBuilder("E");
        for (int i = 0; i < 31; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private void count(String operation, String outcome) {
        meterRegistry.counter("integration.calls", "provider", "pix_sandbox", "operation", operation, "outcome", outcome)
                .increment();
    }
}
