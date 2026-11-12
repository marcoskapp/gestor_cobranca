package br.com.gestorcobranca.costing.application.port.out;

import br.com.gestorcobranca.costing.domain.model.Usage.AiUsage;
import br.com.gestorcobranca.costing.domain.model.Usage.CallUsage;
import br.com.gestorcobranca.costing.domain.model.Usage.LoggedTime;
import br.com.gestorcobranca.costing.domain.model.Usage.MessageCount;
import br.com.gestorcobranca.costing.domain.model.Usage.Posted;
import br.com.gestorcobranca.costing.domain.model.Usage.TemplateSend;
import br.com.gestorcobranca.costing.domain.model.Usage.WorkSpan;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

/**
 * Leitura do uso nos módulos de origem pelas views de contrato {@code costing.src_*} (RLS da assessoria corrente).
 * Períodos em dias de negócio, inclusivos, no fuso de operação.
 */
public interface UsageReader {

    /**
     * Trechos de trabalho das pessoas que tocam o período: interações de pessoas com duração, pós-atendimento das
     * chamadas com credor e respostas escritas no chat (cada mensagem enviada por uma pessoa vale do evento anterior da
     * conversa até o envio, limitado a {@code chatReply}).
     */
    List<WorkSpan> workSpans(LocalDate from, LocalDate to, Duration chatReply);

    List<LoggedTime> loggedTime(LocalDate from, LocalDate to);

    /** Chamadas com conversa, agrupadas por dia, credor, carteira, conta e duração da conversa. */
    List<CallUsage> calls(LocalDate from, LocalDate to);

    /** Custo de IA do assistente e da Importação PDF. */
    List<AiUsage> ai(LocalDate from, LocalDate to);

    /** Mensagens saídas de SMS e e-mail (enviadas ao provedor). */
    List<MessageCount> messages(LocalDate from, LocalDate to);

    /**
     * Envios de template de WhatsApp enviados ao provedor entre {@code from} e {@code to}, em ordem de conversa,
     * categoria e horário (para as janelas de 24 h).
     */
    void templateSends(LocalDate from, LocalDate to, Consumer<TemplateSend> consumer);

    /** Comissão variável dos negociadores (fora as dispensadas): acúmulo na data do pagamento, estorno na data dele. */
    List<Posted> variableCommission(LocalDate from, LocalDate to);

    /** Custas jurídicas adiantadas pela assessoria e não reembolsáveis (fora as canceladas). */
    List<Posted> legalExpenses(LocalDate from, LocalDate to);
}
