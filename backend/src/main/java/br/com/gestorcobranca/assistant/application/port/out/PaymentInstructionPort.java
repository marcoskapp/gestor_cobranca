package br.com.gestorcobranca.assistant.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Geração de Pix para o acordo do assistente (ferramenta {@code generate_payment}). <strong>Sem
 * adaptador</strong>: o grafo de dependências não permite {@code assistant → payment}; a ferramenta só
 * é exposta ao modelo quando houver uma implementação (decisão pendente de ADR — ver
 * docs/tecnico/desenho/assistente.md §11).
 */
public interface PaymentInstructionPort {

    PixInstruction createPix(UUID agreementId, UUID installmentId);

    /** @param copyPaste código "copia e cola" (enviado ao devedor; nunca ao modelo) */
    record PixInstruction(UUID instructionId, BigDecimal amount, LocalDate dueDate, String copyPaste) {
    }
}
