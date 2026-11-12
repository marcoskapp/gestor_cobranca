package br.com.gestorcobranca.scoring.domain.service;

import br.com.gestorcobranca.scoring.domain.model.ContactChannel;
import br.com.gestorcobranca.scoring.domain.model.ContactFact;
import br.com.gestorcobranca.scoring.domain.model.ContactProfileRow;
import br.com.gestorcobranca.scoring.domain.model.ContactProfileRow.SlotCount;
import br.com.gestorcobranca.scoring.domain.model.ContactSlots;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Cálculo puro da inteligência de contato ({@code docs/tecnico/desenho/inteligencia-de-contato.md} §4):
 * agregação dos fatos por contato, taxa suavizada (Beta-Binomial pela taxa da carteira), melhor faixa horária,
 * tentativas até o 1º sucesso, canal preferido e confiança. Só orienta: nenhum resultado muda regra de contato.
 */
public final class ContactProfileCalculator {

    /** Força da priori: equivale a 10 tentativas com a taxa da carteira. */
    public static final int PRIOR_STRENGTH = 10;
    /** Tentativas mínimas para a taxa da carteira (ou da assessoria) servir de priori. */
    public static final int PRIOR_MIN_ATTEMPTS = 30;
    /** Priori neutra quando nem a assessoria tem tentativas suficientes. */
    public static final double NEUTRAL_RATE = 0.5;
    /** Tentativas mínimas numa faixa horária para ela concorrer a melhor horário. */
    public static final int MIN_SLOT_ATTEMPTS = 3;
    /** Respostas mínimas (no total) para indicar canal preferido. */
    public static final int MIN_PREFERENCE_RESPONSES = 2;
    /** Peso do contato iniciado pelo devedor no canal preferido. */
    public static final int DEBTOR_INITIATED_WEIGHT = 2;
    /** Tentativas a partir das quais a confiança é média (e o perfil aparece). */
    public static final int MEDIUM_CONFIDENCE_ATTEMPTS = 5;
    /** Tentativas a partir das quais a confiança é alta. */
    public static final int HIGH_CONFIDENCE_ATTEMPTS = 20;
    /**
     * Janela de resposta de mensagem: mensagem recebida até 72 h depois de uma tentativa (ou de outra mensagem do
     * devedor) na mesma série é resposta ou continuação da conversa, não contato iniciado pelo devedor; e só a
     * tentativa mais recente dessa janela é marcada como respondida.
     */
    public static final Duration REPLY_WINDOW = Duration.ofHours(72);

    private static final double EPSILON = 1e-9;

    private ContactProfileCalculator() {
    }

    public enum Confidence { LOW, MEDIUM, HIGH }

    /** Tentativas e sucessos de uma população (carteira ou assessoria), base da priori. */
    public record RateBase(long attempts, long successes) {

        public static final RateBase NONE = new RateBase(0, 0);

        public RateBase plus(RateBase other) {
            return new RateBase(attempts + other.attempts, successes + other.successes);
        }
    }

    /** Priori Beta(α, β). */
    public record Prior(double alpha, double beta) {

        public static Prior ofRate(double rate) {
            double p = Math.min(1, Math.max(0, rate));
            return new Prior(p * PRIOR_STRENGTH, (1 - p) * PRIOR_STRENGTH);
        }

        /** Taxa média a posteriori: (sucessos + α) ÷ (tentativas + α + β). */
        public double smooth(long successes, long trials) {
            return (successes + alpha) / (trials + alpha + beta);
        }

        public double mean() {
            return alpha / (alpha + beta);
        }
    }

    /**
     * Priori do canal: taxa da carteira no canal (≥ {@value #PRIOR_MIN_ATTEMPTS} tentativas); senão a da assessoria no
     * canal; senão a da assessoria em todos os canais; senão neutra (50%).
     */
    public static Prior prior(RateBase portfolioChannel, RateBase tenantChannel, RateBase tenantAll) {
        for (RateBase base : new RateBase[] {portfolioChannel, tenantChannel, tenantAll}) {
            if (base != null && base.attempts() >= PRIOR_MIN_ATTEMPTS) {
                return Prior.ofRate((double) base.successes() / base.attempts());
            }
        }
        return Prior.ofRate(NEUTRAL_RATE);
    }

    public static Confidence confidence(int attempts) {
        if (attempts >= HIGH_CONFIDENCE_ATTEMPTS) {
            return Confidence.HIGH;
        }
        return attempts >= MEDIUM_CONFIDENCE_ATTEMPTS ? Confidence.MEDIUM : Confidence.LOW;
    }

    /**
     * Perfil por (credor, contato, canal) a partir dos fatos do devedor (qualquer ordem). Tentativas até o 1º sucesso
     * contam na ordem cronológica das tentativas de cada série.
     */
    public static List<ContactProfileRow> aggregate(UUID debtorId, Collection<ContactFact> facts) {
        Map<SeriesKey, List<ContactFact>> series = new LinkedHashMap<>();
        facts.stream().sorted(Comparator.comparing(ContactFact::occurredAt).thenComparing(f -> f.sourceEventId().toString())) // ordem do uuid no PostgreSQL
                .forEach(f -> series.computeIfAbsent(new SeriesKey(f.creditorId(), f.contactId(), f.channel()),
                        k -> new ArrayList<>()).add(f));
        List<ContactProfileRow> rows = new ArrayList<>();
        series.forEach((key, list) -> rows.add(row(debtorId, key, list)));
        return rows;
    }

    /**
     * Uma série (credor, contato, canal) em ordem cronológica. Contato iniciado pelo devedor: ligação recebida (ou
     * interação de entrada) sempre; mensagem recebida só quando não responde a uma tentativa da série nem continua a
     * conversa (outra mensagem do devedor) nas últimas {@link #REPLY_WINDOW} — a resposta já conta como sucesso da
     * tentativa, e contá-la de novo como iniciada seria contar em dobro.
     */
    private static ContactProfileRow row(UUID debtorId, SeriesKey key, List<ContactFact> facts) {
        int attempts = 0;
        int successes = 0;
        int initiated = 0;
        int reads = 0;
        Integer first = null;
        Instant lastSuccess = null;
        Instant lastAttempt = null;
        Instant lastExchange = null; // última tentativa ou mensagem do devedor na série
        SlotCount[] slots = emptySlots();
        for (ContactFact f : facts) {
            switch (f.kind()) {
                case ATTEMPT -> {
                    attempts++;
                    lastAttempt = f.occurredAt();
                    lastExchange = f.occurredAt();
                    int slot = ContactSlots.indexOf(f.localHour());
                    if (slot >= 0) {
                        slots[slot] = slots[slot].plus(new SlotCount(1, f.success() ? 1 : 0, f.occurredAt()));
                    }
                    if (f.success()) {
                        successes++;
                        lastSuccess = ContactSlots.latest(lastSuccess, f.occurredAt());
                        if (first == null) {
                            first = attempts;
                        }
                    }
                }
                case RESPONSE -> {
                    if (!isReply(f, lastExchange)) {
                        initiated++;
                    }
                    lastExchange = f.occurredAt();
                    lastSuccess = ContactSlots.latest(lastSuccess, f.occurredAt());
                }
                case READ -> reads++;
            }
        }
        return new ContactProfileRow(debtorId, key.creditorId(), key.contactId(), key.channel(), attempts, successes,
                initiated, reads, first, lastSuccess, lastAttempt, List.of(slots));
    }

    /** Mensagem recebida até {@link #REPLY_WINDOW} depois da última tentativa ou mensagem do devedor na série. */
    static boolean isReply(ContactFact response, Instant lastExchange) {
        return response.channel().isMessage() && lastExchange != null
                && !lastExchange.isBefore(response.occurredAt().minus(REPLY_WINDOW));
    }

    /**
     * Junta os perfis do mesmo contato e canal de credores diferentes (os credores que o usuário pode ver): somas,
     * últimas datas e, para as tentativas até o 1º sucesso, o menor valor entre os credores.
     */
    public static List<ContactProfileRow> merge(Collection<ContactProfileRow> rows) {
        Map<SeriesKey, ContactProfileRow> merged = new LinkedHashMap<>();
        for (ContactProfileRow r : rows) {
            SeriesKey key = new SeriesKey(null, r.contactId(), r.channel());
            ContactProfileRow current = merged.get(key);
            if (current == null) {
                merged.put(key, new ContactProfileRow(r.debtorId(), null, r.contactId(), r.channel(), r.attempts(),
                        r.successes(), r.debtorInitiated(), r.reads(), r.attemptsToFirstSuccess(), r.lastSuccessAt(),
                        r.lastAttemptAt(), r.slots()));
                continue;
            }
            List<SlotCount> slots = new ArrayList<>(ContactSlots.COUNT);
            for (int i = 0; i < ContactSlots.COUNT; i++) {
                slots.add(current.slots().get(i).plus(r.slots().get(i)));
            }
            Integer first = current.attemptsToFirstSuccess() == null ? r.attemptsToFirstSuccess()
                    : r.attemptsToFirstSuccess() == null ? current.attemptsToFirstSuccess()
                    : Math.min(current.attemptsToFirstSuccess(), r.attemptsToFirstSuccess());
            merged.put(key, new ContactProfileRow(current.debtorId(), null, current.contactId(), current.channel(),
                    current.attempts() + r.attempts(), current.successes() + r.successes(),
                    current.debtorInitiated() + r.debtorInitiated(), current.reads() + r.reads(), first,
                    ContactSlots.latest(current.lastSuccessAt(), r.lastSuccessAt()),
                    ContactSlots.latest(current.lastAttemptAt(), r.lastAttemptAt()), slots));
        }
        return List.copyOf(merged.values());
    }

    /**
     * Avalia o perfil (já filtrado pelo escopo e juntado por contato): taxa suavizada e confiança de cada contato,
     * melhor faixa, taxas por canal e canal preferido. Contatos ordenados pela taxa (maior primeiro), depois pelas
     * tentativas.
     */
    public static Evaluation evaluate(Collection<ContactProfileRow> rows, Function<ContactChannel, Prior> priors) {
        List<ContactEvaluation> contacts = new ArrayList<>();
        Map<ContactChannel, int[]> byChannel = new EnumMap<>(ContactChannel.class);
        int totalAttempts = 0;
        for (ContactProfileRow r : rows) {
            Prior prior = priors.apply(r.channel());
            double rate = prior.smooth(r.successes(), r.attempts());
            Integer slot = bestSlot(r.slots(), prior);
            contacts.add(new ContactEvaluation(r, rate, confidence(r.attempts()), slot,
                    slot == null ? null : prior.smooth(r.slots().get(slot).successes(), r.slots().get(slot).attempts())));
            int[] c = byChannel.computeIfAbsent(r.channel(), k -> new int[4]);
            c[0] += r.attempts();
            c[1] += r.successes();
            c[2] += r.debtorInitiated();
            c[3] += r.reads();
            totalAttempts += r.attempts();
        }
        contacts.sort(Comparator.comparingDouble(ContactEvaluation::rate).reversed()
                .thenComparing(Comparator.comparingInt((ContactEvaluation e) -> e.row().attempts()).reversed())
                .thenComparing(e -> Objects.toString(e.row().contactId(), "")));
        List<ChannelEvaluation> channels = new ArrayList<>();
        int responses = 0;
        for (Map.Entry<ContactChannel, int[]> e : byChannel.entrySet()) {
            int[] c = e.getValue();
            Prior prior = priors.apply(e.getKey());
            long weightedInitiated = (long) DEBTOR_INITIATED_WEIGHT * c[2];
            double preference = prior.smooth(c[1] + weightedInitiated, c[0] + weightedInitiated);
            channels.add(new ChannelEvaluation(e.getKey(), c[0], c[1], c[2], c[3], prior.smooth(c[1], c[0]), preference));
            responses += c[1] + c[2];
        }
        channels.sort(Comparator.comparingDouble(ChannelEvaluation::preference).reversed()
                .thenComparing(Comparator.comparingInt(ChannelEvaluation::responses).reversed()));
        return new Evaluation(contacts, channels, preferred(channels, responses), responses, totalAttempts);
    }

    /** Canal com maior preferência; empate (mesma preferência e mesmas respostas) → sem preferência clara. */
    static ContactChannel preferred(List<ChannelEvaluation> sorted, int responses) {
        if (responses < MIN_PREFERENCE_RESPONSES || sorted.isEmpty() || sorted.getFirst().responses() == 0) {
            return null;
        }
        ChannelEvaluation best = sorted.getFirst();
        if (sorted.size() > 1) {
            ChannelEvaluation second = sorted.get(1);
            if (Math.abs(best.preference() - second.preference()) < EPSILON && best.responses() == second.responses()) {
                return null;
            }
        }
        return best.channel();
    }

    /**
     * Faixa com maior taxa suavizada entre as que têm ≥ {@value #MIN_SLOT_ATTEMPTS} tentativas; empate pela faixa com
     * a tentativa mais recente. Sem faixa elegível → nulo.
     */
    public static Integer bestSlot(List<SlotCount> slots, Prior prior) {
        Integer best = null;
        double bestRate = -1;
        Instant bestLast = null;
        for (int i = 0; i < slots.size(); i++) {
            SlotCount s = slots.get(i);
            if (s.attempts() < MIN_SLOT_ATTEMPTS) {
                continue;
            }
            double rate = prior.smooth(s.successes(), s.attempts());
            boolean better = rate > bestRate + EPSILON
                    || (Math.abs(rate - bestRate) < EPSILON && s.lastAttemptAt() != null
                    && (bestLast == null || s.lastAttemptAt().isAfter(bestLast)));
            if (best == null || better) {
                best = i;
                bestRate = rate;
                bestLast = s.lastAttemptAt();
            }
        }
        return best;
    }

    /** Mediana de valores com contagem (valor → ocorrências); vazio → nulo. */
    public static Double weightedMedian(Map<Integer, Long> counts) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) {
            return null;
        }
        List<Map.Entry<Integer, Long>> sorted = counts.entrySet().stream().filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.comparingByKey()).toList();
        long lowRank = (total - 1) / 2;
        long highRank = total / 2;
        Integer low = null;
        Integer high = null;
        long seen = 0;
        for (Map.Entry<Integer, Long> e : sorted) {
            long next = seen + e.getValue();
            if (low == null && lowRank < next) {
                low = e.getKey();
            }
            if (highRank < next) {
                high = e.getKey();
                break;
            }
            seen = next;
        }
        return (low + high) / 2.0;
    }

    private static SlotCount[] emptySlots() {
        SlotCount[] slots = new SlotCount[ContactSlots.COUNT];
        java.util.Arrays.fill(slots, SlotCount.EMPTY);
        return slots;
    }

    private record SeriesKey(UUID creditorId, UUID contactId, ContactChannel channel) {
    }

    /**
     * @param bestSlot     índice da melhor faixa (ver {@link ContactSlots}), ou nulo
     * @param bestSlotRate taxa suavizada na melhor faixa
     */
    public record ContactEvaluation(ContactProfileRow row, double rate, Confidence confidence, Integer bestSlot,
                                    Double bestSlotRate) {
    }

    /**
     * @param rate       taxa suavizada de sucesso das tentativas no canal
     * @param preference taxa suavizada com peso {@value #DEBTOR_INITIATED_WEIGHT} para o contato iniciado pelo devedor
     */
    public record ChannelEvaluation(ContactChannel channel, int attempts, int successes, int debtorInitiated, int reads,
                                    double rate, double preference) {

        public int responses() {
            return successes + debtorInitiated;
        }
    }

    /**
     * @param preferred      canal preferido, ou nulo (sem preferência clara)
     * @param totalResponses tentativas com sucesso + contatos iniciados pelo devedor
     */
    public record Evaluation(List<ContactEvaluation> contacts, List<ChannelEvaluation> channels,
                             ContactChannel preferred, int totalResponses, int totalAttempts) {
    }
}
