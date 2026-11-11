package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.shared.privacy.DataSharingFact;
import br.com.gestorcobranca.shared.privacy.DataSharingQuery;
import br.com.gestorcobranca.shared.privacy.DataSharingRecipient;
import br.com.gestorcobranca.shared.privacy.DataSharingSource;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reúne os fatos das fontes de compartilhamento ({@link DataSharingSource}, implementadas pelos módulos donos e
 * recebidas por injeção — o compliance não depende deles). Cada fonte roda em transação somente leitura própria, com o
 * ator da requisição (escopo e RLS do tenant); a fonte que falha vai para {@code unavailable} e as demais seguem.
 */
@Component
class DataSharingCollector {

    private static final Logger LOG = LoggerFactory.getLogger(DataSharingCollector.class);
    /** Ordem de exibição: credores, prestadores, jurídico e integrações. */
    private static final List<String> ORDER = List.of("debt", "communication", "telephony", "payment", "assistant",
            "legal", "publicapi");

    private final List<DataSharingSource> sources;
    private final TenantExecution tenantExecution;
    private final CurrentActor currentActor;

    DataSharingCollector(List<DataSharingSource> sources, TenantExecution tenantExecution, CurrentActor currentActor) {
        this.sources = sources.stream().sorted(Comparator.comparingInt(DataSharingCollector::rank)).toList();
        this.tenantExecution = tenantExecution;
        this.currentActor = currentActor;
    }

    record Collected(List<DataSharingFact> facts, List<String> unavailable) {
    }

    record SourcedRecipient(DataSharingRecipient recipient, String source) {
    }

    Collected sharedWith(DataSharingQuery query) {
        List<DataSharingFact> facts = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (DataSharingSource source : sources) {
            run(source, s -> s.sharedWith(query)).ifPresentOrElse(facts::addAll, () -> unavailable.add(source.label()));
        }
        return new Collected(List.copyOf(facts), List.copyOf(unavailable));
    }

    List<SourcedRecipient> recipientsInUse() {
        List<SourcedRecipient> recipients = new ArrayList<>();
        for (DataSharingSource source : sources) {
            run(source, DataSharingSource::recipientsInUse).ifPresent(list -> list.forEach(
                    r -> recipients.add(new SourcedRecipient(r, source.label()))));
        }
        return List.copyOf(recipients);
    }

    List<String> sourceIds() {
        return sources.stream().map(DataSharingSource::id).toList();
    }

    private <T> Optional<T> run(DataSharingSource source, Function<DataSharingSource, T> call) {
        Actor actor = currentActor.get();
        try {
            return Optional.ofNullable(tenantExecution.callAsReadOnly(actor, () -> call.apply(source)));
        } catch (RuntimeException e) {
            // só classes e SQLState: a mensagem pode trazer parâmetros da consulta (dado pessoal) e não vai ao log
            Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
            LOG.atWarn().addKeyValue("source", source.id()).addKeyValue("error", e.getClass().getSimpleName())
                    .addKeyValue("rootCause", root.getClass().getName())
                    .addKeyValue("sqlState", sqlState(e))
                    .log("Fonte de compartilhamento indisponível");
            return Optional.empty();
        }
    }

    /** SQLState da primeira {@link SQLException} da cadeia (ex.: 57014 = tempo esgotado, 42P01 = tabela ausente). */
    static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static int rank(DataSharingSource source) {
        int index = ORDER.indexOf(source.id());
        return index < 0 ? ORDER.size() : index;
    }
}
