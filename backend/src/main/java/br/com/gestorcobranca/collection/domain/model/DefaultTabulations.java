package br.com.gestorcobranca.collection.domain.model;

import java.util.List;

/**
 * Catálogo padrão de tabulações semeado em cada assessoria (o mesmo conteúdo da migration
 * {@code V0601}). A assessoria pode ajustar nomes e flags, desativar e criar outras.
 */
public final class DefaultTabulations {

    public record Entry(String code, Tabulation.Definition definition) {
    }

    private DefaultTabulations() {
    }

    public static List<Entry> catalog() {
        return List.of(
                entry("CPC_NEGOTIATING", "Contato efetivo - em negociação", true, false, false, false, 10),
                entry("PROMISE", "Promessa de pagamento", true, true, false, false, 20),
                entry("CALLBACK", "Retorno agendado", true, false, true, false, 30),
                entry("REFUSED", "Recusa pagar", true, false, false, true, 40),
                entry("ALREADY_PAID", "Alega pagamento", true, false, false, true, 50),
                entry("DISPUTE", "Contesta a dívida", true, false, false, true, 60),
                entry("THIRD_PARTY", "Recado com terceiro", false, false, false, false, 70),
                entry("NO_ANSWER", "Não atende", false, false, false, false, 80),
                entry("BUSY", "Ocupado", false, false, false, false, 90),
                entry("VOICEMAIL", "Caixa postal", false, false, false, false, 100),
                entry("WRONG_CONTACT", "Contato errado / não conhece", false, false, false, true, 110),
                entry("DECEASED", "Informado falecimento", false, false, false, true, 120),
                // registradas automaticamente pelo módulo communication (V1001 nas assessorias existentes)
                entry("MESSAGE_SENT", "Mensagem enviada", false, false, false, false, 130),
                entry("MESSAGE_RECEIVED", "Mensagem recebida", false, false, false, false, 140));
    }

    private static Entry entry(String code, String name, boolean effectiveContact, boolean promise, boolean callback,
                               boolean requiresNote, int order) {
        return new Entry(code, new Tabulation.Definition(name, effectiveContact, promise, callback, requiresNote, order));
    }
}
