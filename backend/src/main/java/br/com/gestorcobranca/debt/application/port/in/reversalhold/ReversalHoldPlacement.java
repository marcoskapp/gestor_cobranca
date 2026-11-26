package br.com.gestorcobranca.debt.application.port.in.reversalhold;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Cria a retenção da cobrança na transação do estorno (chamada pelo payment antes de publicar {@code PaymentReversed});
 * erro aqui desfaz o estorno inteiro (falha fechada, RS-108). Toma a trava compartilhada do devedor (ordem devedor →
 * título → retenção). Titular anonimizado ou eliminado (decisão 1603) não recebe retenção: devolve vazio.
 */
public interface ReversalHoldPlacement {

    Optional<UUID> place(PlaceCommand command);

    /**
     * @param agreementIds   acordos das alocações estornadas (pode haver mais de um: sobra realocada)
     * @param titleIds       títulos afetados (das alocações a títulos e os títulos dos acordos)
     * @param installmentIds parcelas do acordo afetadas
     * @param allocationIds  alocações estornadas (o {@code paymentId} dos eventos)
     * @param informedBy     quem informou o pagamento original, só quando usuário interno
     * @param reversalReason motivo do estorno (enum do payment)
     */
    record PlaceCommand(UUID paymentId, UUID reversalId, UUID creditorId, UUID debtorId, Collection<UUID> agreementIds,
                        Collection<UUID> titleIds, Collection<UUID> installmentIds, Collection<UUID> allocationIds,
                        UUID informedBy, String reversalReason) {
    }
}
