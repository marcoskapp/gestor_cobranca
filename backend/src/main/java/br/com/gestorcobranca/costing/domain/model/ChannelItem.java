package br.com.gestorcobranca.costing.domain.model;

/**
 * Item de custo de canal faturado por um provedor (desenho §3.5 e §13): a gestão informa a fatura do mês por item, e a
 * diferença para a estimativa é rateada só entre os fatos daquele item. Cada item pertence a uma categoria de custo:
 * telefonia ({@link CostCategory#TELEPHONY}); WhatsApp, SMS e e-mail ({@link CostCategory#MESSAGING}); inteligência
 * artificial ({@link CostCategory#AI}, fatura em real — a gestão converte a fatura em dólar).
 */
@org.springframework.modulith.NamedInterface("usecases")
public enum ChannelItem {
    TELEPHONY(CostCategory.TELEPHONY),
    WHATSAPP(CostCategory.MESSAGING),
    SMS(CostCategory.MESSAGING),
    EMAIL(CostCategory.MESSAGING),
    AI(CostCategory.AI);

    private final CostCategory category;

    ChannelItem(CostCategory category) {
        this.category = category;
    }

    public CostCategory category() {
        return category;
    }

    /** Item faturado da tarifa de mensagem (as três categorias do WhatsApp vêm na mesma fatura). */
    public static ChannelItem of(TariffChannel channel) {
        return switch (channel) {
            case WHATSAPP_UTILITY, WHATSAPP_MARKETING, WHATSAPP_AUTHENTICATION -> WHATSAPP;
            case SMS -> SMS;
            case EMAIL -> EMAIL;
        };
    }
}
