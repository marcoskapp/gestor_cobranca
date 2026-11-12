package br.com.gestorcobranca.costing.domain.service;

import br.com.gestorcobranca.costing.domain.model.TariffChannel;
import br.com.gestorcobranca.costing.domain.model.Usage.TemplateSend;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversas de WhatsApp cobradas (desenho §3.2): a empresa inicia a conversa com um template e o provedor cobra uma vez
 * por <b>janela de 24 h</b> por conversa e categoria do template (utilidade, marketing, autenticação). Outro template
 * da mesma categoria dentro da janela não gera nova cobrança; depois de 24 h do início da janela, abre outra. Mensagens
 * livres (sem template) e respostas do devedor não abrem janela.
 * <p>
 * Recebe os envios em qualquer ordem por conversa, mas em ordem cronológica dentro de cada conversa e categoria
 * (a leitura ordena por conversa, categoria e horário).
 */
public final class WhatsAppConversations {

    public static final Duration WINDOW = Duration.ofHours(24);

    private record WindowKey(UUID conversationId, TariffChannel channel) {
    }

    private final Map<WindowKey, Instant> openedAt = new HashMap<>();

    /**
     * @return a tarifa a cobrar quando o envio abre uma janela nova; vazio quando cai numa janela aberta ou o template
     * não tem categoria conhecida
     */
    public Optional<TariffChannel> accept(TemplateSend send) {
        Optional<TariffChannel> channel = TariffChannel.forWhatsAppCategory(send.providerCategory());
        if (channel.isEmpty() || send.sentAt() == null) {
            return Optional.empty();
        }
        WindowKey key = new WindowKey(send.conversationId(), channel.get());
        Instant start = openedAt.get(key);
        if (start != null && send.sentAt().isBefore(start.plus(WINDOW))) {
            return Optional.empty();
        }
        openedAt.put(key, send.sentAt());
        return channel;
    }

    /** Esquece as janelas de uma conversa já processada (a leitura vem ordenada por conversa). */
    public void forget(UUID conversationId) {
        openedAt.keySet().removeIf(k -> k.conversationId().equals(conversationId));
    }
}
