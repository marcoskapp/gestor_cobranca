package br.com.gestorcobranca.scoring.domain.service;

import br.com.gestorcobranca.scoring.domain.model.CaseFact;
import br.com.gestorcobranca.scoring.domain.model.CaseInputs;
import br.com.gestorcobranca.scoring.domain.model.FactKind;
import br.com.gestorcobranca.scoring.domain.model.Feature;
import br.com.gestorcobranca.scoring.domain.model.FeatureVector;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Calcula as features de um caso no instante {@code at} a partir da posição do caso e dos fatos do
 * devedor no credor. <b>Sem vazamento</b>: só entram fatos conhecidos até {@code at}
 * ({@code occurredAt <= at}); o mesmo cálculo é usado na escoragem e na geração dos snapshots de
 * treino.
 * <ul>
 *   <li>contatos, promessas: fatos do próprio caso;</li>
 *   <li>pagamentos: do devedor na carteira do caso (pagamentos não trazem o caso), líquidos de estorno;</li>
 *   <li>acordos e canais com resposta: do devedor no credor (relacionamento com o credor).</li>
 * </ul>
 */
public final class FeatureCalculator {

    static final long MAX_DAYS_OVERDUE = 3650;
    private static final Duration D90 = Duration.ofDays(90);
    private static final Duration D365 = Duration.ofDays(365);
    private static final Set<String> AGREEMENT_SEEN = Set.of("FORMALIZED", "IN_PAYMENT", "SETTLED", "BROKEN",
            "CANCELLED", "RENEGOTIATED");

    private FeatureCalculator() {
    }

    /**
     * @param facts         fatos do devedor (qualquer carteira/credor; filtrados aqui)
     * @param portfolioRate taxa histórica de pagamento da carteira na data (null = sem histórico)
     */
    public static FeatureVector compute(CaseInputs c, Collection<CaseFact> facts, Instant at, Double portfolioRate) {
        Map<Feature, Double> v = new EnumMap<>(Feature.class);
        v.put(Feature.DAYS_OVERDUE, (double) Math.min(Math.max(0, c.daysOverdue()), MAX_DAYS_OVERDUE));
        v.put(Feature.OPEN_BALANCE, c.openBalance() == null ? 0.0 : Math.max(0.0, c.openBalance().doubleValue()));
        v.put(Feature.OPEN_TITLES, (double) Math.max(0, c.openTitles()));
        v.put(Feature.CASE_AGE_DAYS, c.openedAt() == null ? 0.0 : days(c.openedAt(), at));
        v.put(Feature.DAYS_SINCE_EFFECTIVE_CONTACT,
                c.lastEffectiveContactAt() == null || c.lastEffectiveContactAt().isAfter(at) ? null
                        : days(c.lastEffectiveContactAt(), at));
        v.put(Feature.ACTIVE_AGREEMENT, stage(c, "AGREEMENT") ? 1.0 : 0.0);
        v.put(Feature.PENDING_PROMISE, stage(c, "PROMISE") ? 1.0 : 0.0);
        v.put(Feature.PORTFOLIO_RECOVERY_RATE, portfolioRate);

        List<CaseFact> known = facts.stream()
                .filter(f -> Objects.equals(f.debtorId(), c.debtorId()) && !f.occurredAt().isAfter(at))
                .toList();
        Instant since90 = at.minus(D90);
        int attempts = 0;
        int effective = 0;
        Set<String> responsive = new HashSet<>();
        Map<UUID, CaseFact> promises = new HashMap<>();
        Set<UUID> agreements = new HashSet<>();
        Set<UUID> brokenAgreements = new HashSet<>();
        Map<UUID, CaseFact> payments = new HashMap<>();
        Set<UUID> reversed = new HashSet<>();
        for (CaseFact f : known) {
            boolean ofCase = c.caseId().equals(f.caseId());
            boolean ofCreditor = c.creditorId().equals(f.creditorId());
            boolean recent = f.occurredAt().isAfter(since90);
            switch (f.kind()) {
                case CONTACT_ATTEMPT -> attempts += ofCase && recent ? 1 : 0;
                case EFFECTIVE_CONTACT -> effective += ofCase && recent ? 1 : 0;
                case DEBTOR_RESPONSE -> {
                    if (recent && f.channel() != null && (ofCase || (f.caseId() == null && ofCreditor))) {
                        responsive.add(f.channel());
                    }
                }
                case PROMISE -> {
                    if (ofCase && f.referenceId() != null) {
                        promises.merge(f.referenceId(), f, FeatureCalculator::latest);
                    }
                }
                case AGREEMENT -> {
                    if (ofCreditor && f.referenceId() != null && AGREEMENT_SEEN.contains(f.status())) {
                        agreements.add(f.referenceId());
                        if ("BROKEN".equals(f.status())) {
                            brokenAgreements.add(f.referenceId());
                        }
                    }
                }
                case PAYMENT -> {
                    if (samePortfolio(c, f) && f.referenceId() != null) {
                        payments.putIfAbsent(f.referenceId(), f);
                    }
                }
                case PAYMENT_REVERSAL -> {
                    if (f.referenceId() != null) {
                        reversed.add(f.referenceId());
                    }
                }
            }
        }
        long kept = promises.values().stream().filter(p -> "KEPT".equals(p.status())).count();
        long broken = promises.values().stream().filter(p -> "BROKEN".equals(p.status())).count();
        List<CaseFact> netPayments = payments.values().stream().filter(p -> !reversed.contains(p.referenceId()))
                .filter(p -> !p.effectiveAt().isAfter(at)).toList();
        Instant since365 = at.minus(D365);
        long recentPayments = netPayments.stream().filter(p -> p.effectiveAt().isAfter(since365)).count();
        Instant lastPayment = netPayments.stream().map(CaseFact::effectiveAt).max(Comparator.naturalOrder()).orElse(null);

        v.put(Feature.CONTACT_ATTEMPTS_90D, (double) attempts);
        v.put(Feature.EFFECTIVE_CONTACTS_90D, (double) effective);
        v.put(Feature.RESPONSIVE_CHANNELS_90D, (double) responsive.size());
        v.put(Feature.PROMISES_KEPT, (double) kept);
        v.put(Feature.PROMISES_BROKEN, (double) broken);
        v.put(Feature.PRIOR_AGREEMENTS, (double) agreements.size());
        v.put(Feature.BROKEN_AGREEMENTS, (double) brokenAgreements.size());
        v.put(Feature.PARTIAL_PAYMENTS_365D, (double) recentPayments);
        v.put(Feature.DAYS_SINCE_LAST_PAYMENT, lastPayment == null ? null : days(lastPayment, at));
        return FeatureVector.of(v);
    }

    private static boolean samePortfolio(CaseInputs c, CaseFact f) {
        return c.portfolioId().equals(f.portfolioId());
    }

    private static boolean stage(CaseInputs c, String stage) {
        return stage.equals(c.status()) || stage.equals(c.resumeStatus());
    }

    /** Situação mais recente da promessa (empate: o fato que chegou depois). */
    private static CaseFact latest(CaseFact a, CaseFact b) {
        return b.occurredAt().isBefore(a.occurredAt()) ? a : b;
    }

    private static double days(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toDays());
    }
}
