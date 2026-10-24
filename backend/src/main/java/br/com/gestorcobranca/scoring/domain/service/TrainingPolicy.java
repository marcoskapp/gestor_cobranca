package br.com.gestorcobranca.scoring.domain.service;

import br.com.gestorcobranca.scoring.domain.model.TrainingRow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Regras do treino (puras):
 * <ol>
 *   <li><b>Critério mínimo de dados</b> — casos distintos com desfecho ≥ {@code minCases} e casos
 *       distintos com pagamento ≥ {@code minPositives}; abaixo disso, o modelo estatístico não é
 *       treinado e a assessoria fica no scorecard;</li>
 *   <li><b>Divisão temporal</b> (fora do tempo) — as datas de referência mais recentes, somando
 *       cerca de {@code validationShare} das observações, formam a validação; o treino usa só datas
 *       cuja janela de desfecho terminou antes do início da validação (carência de
 *       {@code outcomeDays} dias), para que nenhum desfecho do treino aconteça no período validado;</li>
 *   <li>cada partição precisa de ao menos {@code minPartitionPositives} positivos e negativos.</li>
 * </ol>
 */
public final class TrainingPolicy {

    private TrainingPolicy() {
    }

    public record Criteria(int minCases, int minPositives, int minPartitionPositives, double validationShare,
                           int outcomeDays) {
    }

    /**
     * @param reasons motivos de recusa (códigos estáveis); vazio = apto
     */
    public record Split(List<TrainingRow> training, List<TrainingRow> validation, long distinctCases,
                        long distinctPositiveCases, List<String> reasons) {

        public boolean sufficient() {
            return reasons.isEmpty();
        }

        public LocalDate trainingFrom() {
            return training.stream().map(TrainingRow::referenceDate).min(LocalDate::compareTo).orElse(null);
        }

        public LocalDate trainingTo() {
            return training.stream().map(TrainingRow::referenceDate).max(LocalDate::compareTo).orElse(null);
        }

        public LocalDate validationFrom() {
            return validation.stream().map(TrainingRow::referenceDate).min(LocalDate::compareTo).orElse(null);
        }

        public LocalDate validationTo() {
            return validation.stream().map(TrainingRow::referenceDate).max(LocalDate::compareTo).orElse(null);
        }
    }

    public static Split split(List<TrainingRow> rows, Criteria criteria) {
        List<String> reasons = new ArrayList<>();
        Set<UUID> cases = rows.stream().map(TrainingRow::caseId).collect(Collectors.toSet());
        Set<UUID> positiveCases = rows.stream().filter(TrainingRow::paid).map(TrainingRow::caseId)
                .collect(Collectors.toSet());
        if (cases.size() < criteria.minCases()) {
            reasons.add("min_cases");
        }
        if (positiveCases.size() < criteria.minPositives()) {
            reasons.add("min_positives");
        }
        TreeMap<LocalDate, Integer> perDate = new TreeMap<>();
        rows.forEach(r -> perDate.merge(r.referenceDate(), 1, Integer::sum));
        if (perDate.size() < 2) {
            reasons.add("single_reference_date");
            return new Split(List.of(), List.of(), cases.size(), positiveCases.size(), reasons);
        }
        // datas mais recentes até alcançar a fração de validação (ao menos uma data)
        int target = (int) Math.ceil(rows.size() * criteria.validationShare());
        int accumulated = 0;
        LocalDate validationStart = perDate.lastKey();
        for (LocalDate d : perDate.descendingKeySet()) {
            if (accumulated >= target && accumulated > 0) {
                break;
            }
            accumulated += perDate.get(d);
            validationStart = d;
        }
        LocalDate trainingEnd = validationStart.minusDays(criteria.outcomeDays());
        LocalDate start = validationStart;
        List<TrainingRow> validation = rows.stream().filter(r -> !r.referenceDate().isBefore(start)).toList();
        List<TrainingRow> training = rows.stream().filter(r -> !r.referenceDate().isAfter(trainingEnd)).toList();
        check(training, criteria.minPartitionPositives(), "training", reasons);
        check(validation, criteria.minPartitionPositives(), "validation", reasons);
        return new Split(training, validation, cases.size(), positiveCases.size(), List.copyOf(reasons));
    }

    private static void check(List<TrainingRow> part, int minimum, String name, List<String> reasons) {
        long positives = part.stream().filter(TrainingRow::paid).count();
        long negatives = part.size() - positives;
        if (positives < minimum || negatives < minimum) {
            reasons.add(name + "_partition_too_small");
        }
    }
}
