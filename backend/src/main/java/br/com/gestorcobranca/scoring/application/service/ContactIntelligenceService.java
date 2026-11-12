package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.scoring.application.port.in.ContactIntelligenceUseCases;
import br.com.gestorcobranca.scoring.application.port.out.ContactFactStore;
import br.com.gestorcobranca.scoring.application.port.out.ContactFactStore.ChannelCount;
import br.com.gestorcobranca.scoring.application.port.out.ContactFactStore.FirstSuccessCount;
import br.com.gestorcobranca.scoring.application.port.out.ContactFactStore.InsightFilter;
import br.com.gestorcobranca.scoring.application.port.out.ContactFactStore.OutcomeCount;
import br.com.gestorcobranca.scoring.application.port.out.ContactFactStore.PersonTypeCount;
import br.com.gestorcobranca.scoring.domain.model.ContactChannel;
import br.com.gestorcobranca.scoring.domain.model.ContactProfileRow;
import br.com.gestorcobranca.scoring.domain.model.ContactSlots;
import br.com.gestorcobranca.scoring.domain.service.ContactProfileCalculator;
import br.com.gestorcobranca.scoring.domain.service.ContactProfileCalculator.ChannelEvaluation;
import br.com.gestorcobranca.scoring.domain.service.ContactProfileCalculator.ContactEvaluation;
import br.com.gestorcobranca.scoring.domain.service.ContactProfileCalculator.Evaluation;
import br.com.gestorcobranca.scoring.domain.service.ContactProfileCalculator.Prior;
import br.com.gestorcobranca.scoring.domain.service.ContactProfileCalculator.RateBase;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * Leitura da inteligência de contato: perfil do devedor (cartão "Melhor forma de contato") e painel da carteira.
 * Toda resposta é filtrada pelo escopo de credores do usuário; as explicações em texto mostram a base de cada número.
 */
@Service
@Transactional(readOnly = true)
class ContactIntelligenceService implements ContactIntelligenceUseCases {

    /** Tentativas mínimas para uma célula ou linha do painel exibir taxa. */
    static final int MIN_INSIGHT_ATTEMPTS = 10;
    /** Posição a partir da qual a curva agrupa ("11 ou mais"). */
    static final int CURVE_TAIL = 11;
    static final long MAX_RANGE_DAYS = 400;
    static final String PROFILE_NOTICE = "Sugestão para orientar o contato: não muda a política de contato (horários, "
            + "limites e canais permitidos) nem decide sozinha. As taxas são ajustadas pela média da carteira (ou da "
            + "assessoria, sem dados suficientes na carteira) para que poucas tentativas não pareçam certeza.";
    static final String INSIGHTS_NOTICE = "Indicadores para orientar a operação: não mudam a política de contato nem "
            + "as réguas. Acordos em vigor e cancelados ficam fora da taxa de cumprimento.";
    private static final List<String> BUCKETS = List.of("0-1", "2-3", "4-6", "7-10", "11-20", "21+");

    private final ContactFactStore store;
    private final DebtorUseCases debtors;
    private final CaseUseCases cases;
    private final AccessScope accessScope;

    ContactIntelligenceService(ContactFactStore store, DebtorUseCases debtors, CaseUseCases cases,
                               AccessScope accessScope) {
        this.store = store;
        this.debtors = debtors;
        this.cases = cases;
        this.accessScope = accessScope;
    }

    @Override
    public ContactProfileView profile(UUID debtorId, UUID caseId) {
        debtors.get(debtorId); // devedor visível no escopo (404 fora dele)
        UUID caseCreditor = null;
        UUID portfolioId = null;
        if (caseId != null) {
            CaseView c = cases.get(caseId); // caso visível no escopo (404 fora dele)
            if (!debtorId.equals(c.debtorId())) {
                throw new ResourceNotFoundException("collection_case", caseId);
            }
            caseCreditor = c.creditorId();
            portfolioId = c.portfolioId();
        }
        UUID onlyCreditor = caseCreditor;
        List<ContactProfileRow> visible = store.profilesOfDebtor(debtorId).stream()
                .filter(r -> onlyCreditor != null ? onlyCreditor.equals(r.creditorId())
                        : accessScope.hasAllCreditors() || (r.creditorId() != null
                        && accessScope.canAccessCreditor(r.creditorId())))
                .toList();
        Function<ContactChannel, Prior> priors = priors(portfolioId);
        Evaluation evaluation = ContactProfileCalculator.evaluate(ContactProfileCalculator.merge(visible), priors);
        int total = evaluation.totalAttempts();
        int min = ContactProfileCalculator.MEDIUM_CONFIDENCE_ATTEMPTS;
        boolean sufficient = total >= min;
        String emptyReason = sufficient ? null : total == 0
                ? "Ainda não há tentativas de contato registradas; o perfil aparece a partir de " + min + "."
                : "Ainda são " + total + (total == 1 ? " tentativa" : " tentativas") + "; o perfil aparece a partir de "
                + min + ".";
        Double median = portfolioId == null ? null : store.medianAttemptsToFirstSuccess(portfolioId);
        return new ContactProfileView(debtorId, caseId, total, min, sufficient, emptyReason,
                evaluation.preferred() == null ? null : evaluation.preferred().name(), preferenceText(evaluation),
                evaluation.channels().stream().map(ContactIntelligenceService::channelView).toList(),
                evaluation.contacts().stream().map(ContactIntelligenceService::contactView).toList(),
                decimal(median, 1), PROFILE_NOTICE, TimeConfig.BUSINESS_ZONE_ID);
    }

    @Override
    public ContactInsightsView insights(LocalDate from, LocalDate to, UUID creditorId, UUID portfolioId,
                                        String personType) {
        Guard.require(from != null && to != null && !to.isBefore(from), "scoring.range_invalid", "Período inválido");
        Guard.require(ChronoUnit.DAYS.between(from, to) <= MAX_RANGE_DAYS, "scoring.range_too_long",
                "Período máximo de 400 dias");
        String type = personType == null || personType.isBlank() ? null : personType.trim().toUpperCase(Locale.ROOT);
        if (type != null && !Set.of("INDIVIDUAL", "COMPANY").contains(type)) {
            throw new BusinessRuleException("scoring.person_type_invalid", "Tipo de pessoa inválido");
        }
        Set<UUID> creditors = accessScope.restrictCreditors(creditorId == null ? Set.of() : Set.of(creditorId));
        boolean nothingVisible = (creditorId != null || !accessScope.hasAllCreditors()) && creditors.isEmpty();
        InsightFilter filter = new InsightFilter(from.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant(), creditors, portfolioId, type);
        List<ContactFactStore.HeatCell> heat = nothingVisible ? List.of() : store.heatmap(filter);
        List<ChannelCount> channels = nothingVisible ? List.of() : store.channels(filter);
        List<FirstSuccessCount> curve = nothingVisible ? List.of() : store.firstSuccess(filter);
        List<PersonTypeCount> people = nothingVisible ? List.of() : store.attemptsByPersonType(filter);
        List<ContactFactStore.ContactOriginCount> origins = nothingVisible ? List.of()
                : store.attemptsByContactOrigin(filter);
        List<OutcomeCount> outcomes = nothingVisible ? List.of() : store.outcomes(filter);

        long attempts = channels.stream().mapToLong(ChannelCount::attempts).sum();
        long successes = channels.stream().mapToLong(ChannelCount::successes).sum();
        long initiated = channels.stream().mapToLong(ChannelCount::debtorInitiated).sum();
        long agreements = outcomes.stream().mapToLong(OutcomeCount::agreements).sum();
        long completed = sum(outcomes, "COMPLETED");
        long broken = sum(outcomes, "BROKEN");
        Totals totals = new Totals(attempts, successes, ratio(successes, attempts, 1), initiated, agreements, completed,
                broken, ratio(completed, completed + broken, 1));

        return new ContactInsightsView(from, to, creditorId, portfolioId, type, MIN_INSIGHT_ATTEMPTS, totals,
                heat.stream().map(c -> new HeatCellView(c.weekday(), c.startHour(), c.attempts(), c.successes(),
                        ratio(c.successes(), c.attempts(), MIN_INSIGHT_ATTEMPTS))).toList(),
                curve(curve), curve.stream().filter(c -> c.position() == null).mapToLong(FirstSuccessCount::series).sum(),
                channels.stream().map(c -> new ChannelInsightView(c.channel().name(), c.attempts(), c.successes(),
                        ratio(c.successes(), c.attempts(), MIN_INSIGHT_ATTEMPTS), c.debtorInitiated(), c.reads()))
                        .toList(),
                buckets(outcomes), decimal(median(outcomes, null, false), 1),
                decimal(median(outcomes, "COMPLETED", false), 1), decimal(median(outcomes, null, true), 1),
                byPersonType(people, outcomes),
                origins.stream().map(o -> new ContactOriginView(o.origin(), o.attempts(), o.successes(),
                        ratio(o.successes(), o.attempts(), MIN_INSIGHT_ATTEMPTS))).toList(), INSIGHTS_NOTICE);
    }

    // ---------------------------------------------------------------- perfil

    /**
     * Priori por canal a partir da base materializada na manutenção diária ({@code ContactRateBaseService}); antes da
     * primeira manutenção a base está vazia e a priori é neutra.
     */
    private Function<ContactChannel, Prior> priors(UUID portfolioId) {
        Map<ContactChannel, RateBase> portfolio = portfolioId == null ? Map.of() : store.rates(portfolioId);
        Map<ContactChannel, RateBase> tenant = store.rates(null);
        RateBase all = tenant.values().stream().reduce(RateBase.NONE, RateBase::plus);
        Map<ContactChannel, Prior> out = new EnumMap<>(ContactChannel.class);
        for (ContactChannel c : ContactChannel.values()) {
            out.put(c, ContactProfileCalculator.prior(portfolio.get(c), tenant.get(c), all));
        }
        return out::get;
    }

    private static ContactView contactView(ContactEvaluation e) {
        ContactProfileRow r = e.row();
        SlotView slot = null;
        String slotText = null;
        if (e.bestSlot() != null) {
            int i = e.bestSlot();
            ContactProfileRow.SlotCount s = r.slots().get(i);
            slot = new SlotView(ContactSlots.startHour(i), ContactSlots.endHour(i), s.attempts(), s.successes(),
                    decimal(e.bestSlotRate(), 4));
            slotText = "Melhor horário: " + slotLabel(i) + " (" + countText(r.channel(), s.successes(), s.attempts())
                    + ").";
        }
        String text = contactText(r.channel(), r.successes(), r.attempts(), e.rate(), r.debtorInitiated(), r.reads());
        if (e.confidence() == ContactProfileCalculator.Confidence.LOW) {
            text += " Dados insuficientes para concluir (menos de "
                    + ContactProfileCalculator.MEDIUM_CONFIDENCE_ATTEMPTS + " tentativas).";
        }
        return new ContactView(r.contactId(), r.channel().name(), r.attempts(), r.successes(), r.debtorInitiated(),
                r.reads(), decimal(e.rate(), 4), e.confidence().name(), slot, r.attemptsToFirstSuccess(),
                r.lastSuccessAt(), text, slotText);
    }

    private static ChannelView channelView(ChannelEvaluation c) {
        return new ChannelView(c.channel().name(), c.attempts(), c.successes(), c.debtorInitiated(), c.reads(),
                decimal(c.rate(), 4), contactText(c.channel(), c.successes(), c.attempts(), c.rate(), c.debtorInitiated(),
                c.reads()));
    }

    static String preferenceText(Evaluation e) {
        if (e.preferred() != null) {
            ChannelEvaluation c = e.channels().stream().filter(x -> x.channel() == e.preferred()).findFirst()
                    .orElseThrow();
            String text = "Prefere " + CHANNEL_NAMES.get(c.channel()) + ": " + plural(c.responses(), "resposta", "respostas");
            if (c.attempts() > 0) {
                text += " em " + plural(c.attempts(), "tentativa", "tentativas") + " no canal";
            }
            if (c.debtorInitiated() > 0) {
                text += ", " + c.debtorInitiated() + (c.debtorInitiated() == 1 ? " iniciada" : " iniciadas")
                        + " pelo devedor";
            }
            return text + ".";
        }
        int min = ContactProfileCalculator.MIN_PREFERENCE_RESPONSES;
        if (e.totalResponses() < min) {
            return "Sem preferência clara: " + (e.totalResponses() == 0 ? "ainda não há respostas"
                    : "ainda é " + plural(e.totalResponses(), "resposta", "respostas")) + "; a preferência aparece a "
                    + "partir de " + min + ".";
        }
        return "Sem preferência clara: os canais responderam de forma parecida.";
    }

    private static final Map<ContactChannel, String> CHANNEL_NAMES = Map.of(ContactChannel.PHONE, "ligação",
            ContactChannel.WHATSAPP, "WhatsApp", ContactChannel.SMS, "SMS", ContactChannel.EMAIL, "e-mail",
            ContactChannel.OTHER, "outros canais");

    /** Substantivo (singular, plural) e o sucesso (singular, plural) de cada canal. */
    private static final Map<ContactChannel, String[]> WORDS = Map.of(
            ContactChannel.PHONE, new String[] {"ligação", "ligações", "atendida", "atendidas"},
            ContactChannel.WHATSAPP, new String[] {"mensagem de WhatsApp", "mensagens de WhatsApp", "respondida",
                    "respondidas"},
            ContactChannel.SMS, new String[] {"SMS", "SMS", "respondido", "respondidos"},
            ContactChannel.EMAIL, new String[] {"e-mail", "e-mails", "respondido", "respondidos"},
            ContactChannel.OTHER, new String[] {"tentativa", "tentativas", "efetiva", "efetivas"});

    static String countText(ContactChannel channel, int successes, int attempts) {
        String[] w = WORDS.get(channel);
        boolean one = attempts == 1;
        return successes + " de " + attempts + " " + (one ? w[0] : w[1]) + " " + (one ? w[2] : w[3]);
    }

    static String contactText(ContactChannel channel, int successes, int attempts, double rate, int initiated,
                              int reads) {
        StringBuilder text = new StringBuilder();
        if (attempts == 0) {
            text.append("Sem tentativas por aqui.");
        } else {
            text.append(countText(channel, successes, attempts)).append(" (taxa ajustada ").append(percent(rate))
                    .append(").");
        }
        if (initiated > 0) {
            text.append(" O devedor fez contato ").append(initiated == 1 ? "1 vez" : initiated + " vezes")
                    .append(" por aqui.");
        }
        if (reads > 0) {
            text.append(" ").append(reads == 1 ? "1 mensagem lida" : reads + " mensagens lidas")
                    .append(" (leitura não conta como resposta).");
        }
        return text.toString();
    }

    static String slotLabel(int index) {
        return ContactSlots.startHour(index) + "h–" + ContactSlots.endHour(index) + "h";
    }

    // ---------------------------------------------------------------- painel

    private static List<FirstSuccessView> curve(List<FirstSuccessCount> counts) {
        long total = counts.stream().mapToLong(FirstSuccessCount::series).sum();
        Map<Integer, Long> byPosition = new TreeMap<>();
        for (FirstSuccessCount c : counts) {
            if (c.position() != null) {
                byPosition.merge(Math.min(c.position(), CURVE_TAIL), c.series(), Long::sum);
            }
        }
        List<FirstSuccessView> out = new ArrayList<>();
        long cumulative = 0;
        for (int p = 1; p <= CURVE_TAIL && total > 0; p++) {
            long n = byPosition.getOrDefault(p, 0L);
            cumulative += n;
            out.add(new FirstSuccessView(p, n, BigDecimal.valueOf(cumulative)
                    .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_EVEN)));
        }
        return out;
    }

    static String bucketOf(int attempts) {
        if (attempts <= 1) {
            return "0-1";
        }
        if (attempts <= 3) {
            return "2-3";
        }
        if (attempts <= 6) {
            return "4-6";
        }
        if (attempts <= 10) {
            return "7-10";
        }
        return attempts <= 20 ? "11-20" : "21+";
    }

    private static List<AgreementBucketView> buckets(List<OutcomeCount> outcomes) {
        Map<String, long[]> byBucket = new LinkedHashMap<>();
        BUCKETS.forEach(b -> byBucket.put(b, new long[5]));
        for (OutcomeCount o : outcomes) {
            long[] c = byBucket.get(bucketOf(o.attemptsBefore()));
            c[0] += o.agreements();
            switch (o.finalStatus()) {
                case "COMPLETED" -> c[1] += o.agreements();
                case "BROKEN" -> c[2] += o.agreements();
                case "ACTIVE" -> c[3] += o.agreements();
                default -> c[4] += o.agreements();
            }
        }
        if (outcomes.isEmpty()) {
            return List.of();
        }
        return byBucket.entrySet().stream().map(e -> {
            long[] c = e.getValue();
            return new AgreementBucketView(e.getKey(), c[0], c[1], c[2], c[3], c[4], ratio(c[1], c[1] + c[2], 1));
        }).toList();
    }

    /** Mediana das tentativas antes do acordo (ou dos dias do 1º contato efetivo ao acordo). */
    private static Double median(List<OutcomeCount> outcomes, String status, boolean days) {
        Map<Integer, Long> counts = new TreeMap<>();
        for (OutcomeCount o : outcomes) {
            if (status != null && !status.equals(o.finalStatus())) {
                continue;
            }
            Integer value = days ? o.daysFirstEffective() : Integer.valueOf(o.attemptsBefore());
            if (value != null) {
                counts.merge(value, o.agreements(), Long::sum);
            }
        }
        return ContactProfileCalculator.weightedMedian(counts);
    }

    private static List<PersonTypeView> byPersonType(List<PersonTypeCount> people, List<OutcomeCount> outcomes) {
        List<PersonTypeView> out = new ArrayList<>();
        for (String type : List.of("INDIVIDUAL", "COMPANY")) {
            long attempts = 0;
            long successes = 0;
            for (PersonTypeCount p : people) {
                if (type.equals(p.personType())) {
                    attempts += p.attempts();
                    successes += p.successes();
                }
            }
            List<OutcomeCount> mine = outcomes.stream().filter(o -> type.equals(o.personType())).toList();
            long agreements = mine.stream().mapToLong(OutcomeCount::agreements).sum();
            if (attempts == 0 && agreements == 0) {
                continue;
            }
            long completed = sum(mine, "COMPLETED");
            long broken = sum(mine, "BROKEN");
            out.add(new PersonTypeView(type, attempts, successes, ratio(successes, attempts, MIN_INSIGHT_ATTEMPTS),
                    agreements, completed, broken, ratio(completed, completed + broken, 1)));
        }
        return out;
    }

    private static long sum(List<OutcomeCount> outcomes, String status) {
        return outcomes.stream().filter(o -> status.equals(o.finalStatus())).mapToLong(OutcomeCount::agreements).sum();
    }

    // ---------------------------------------------------------------- formatação

    /** Razão com 4 casas, ou nulo abaixo do mínimo de observações. */
    private static BigDecimal ratio(long numerator, long denominator, long minimum) {
        if (denominator <= 0 || denominator < minimum) {
            return null;
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal decimal(Double value, int scale) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_EVEN);
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    static String percent(double rate) {
        return Math.round(rate * 100) + "%";
    }
}
