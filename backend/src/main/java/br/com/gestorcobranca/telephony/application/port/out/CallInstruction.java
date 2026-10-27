package br.com.gestorcobranca.telephony.application.port.out;

/**
 * Instrução da chamada devolvida ao provedor na resposta de um webhook que espera instruções
 * (TwiML da Twilio, XML da Plivo): o domínio decide, o adaptador traduz para o formato do
 * provedor. Nunca contém mais dados pessoais que o necessário para a própria ligação (o número do
 * devedor só em {@link DialDebtor}, no corpo da resposta, nunca em URL).
 */
public sealed interface CallInstruction {

    /** Sem instrução (status/eventos): resposta vazia aceita pelo provedor. */
    record None() implements CallInstruction {
    }

    /**
     * Operador atendeu no clique para ligar: discar para o devedor e conectar.
     *
     * @param announceRecording tocar o aviso de gravação para o devedor ao atender
     */
    record DialDebtor(String destination, String callerId, int ringTimeoutSeconds, boolean record,
                      boolean announceRecording) implements CallInstruction {
    }

    /**
     * Chamada atendida aguardando a decisão do discador (AMD ou operador livre): manter na linha.
     *
     * @param holdSeconds tempo máximo de espera; esgotado, o provedor pede novas instruções
     */
    record Hold(boolean announceRecording, boolean record, int holdSeconds) implements CallInstruction {
    }

    /**
     * Conectar ao ramal do operador escolhido pelo domínio.
     *
     * @param callerId bina da conta (exigida quando o ramal é um telefone E.164)
     */
    record ConnectAgent(String agentEndpoint, String callerId, boolean record, boolean announceRecording)
            implements CallInstruction {
    }

    /** Aviso ao devedor ao atender (antes de conectar ao operador). */
    record Announce() implements CallInstruction {
    }

    /** Encerrar, com a mensagem informativa de abandono quando {@code abandonMessage}. */
    record Hangup(boolean abandonMessage) implements CallInstruction {
    }
}
