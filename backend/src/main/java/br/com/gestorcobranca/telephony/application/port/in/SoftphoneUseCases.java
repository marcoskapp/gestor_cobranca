package br.com.gestorcobranca.telephony.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Softphone no navegador (WebRTC) do <b>próprio</b> operador: para cada conta ativa cujo ramal do
 * operador é uma identidade de navegador da plataforma (ex.: {@code client:<identidade>} na Twilio),
 * um token de acesso de curta duração do SDK de voz do provedor, restrito a essa identidade (somente
 * receber chamadas, salvo TwiML App configurado na conta). Nunca emite token para outro usuário.
 */
public interface SoftphoneUseCases {

    /** @throws br.com.gestorcobranca.shared.domain.ConflictException operador sem ramal habilitado */
    List<SoftphoneSession> sessions();

    /**
     * @param token   credencial de curta duração (resposta {@code no-store}; nunca registrar em log)
     * @param options opções públicas para o SDK (ex.: {@code edge})
     */
    record SoftphoneSession(UUID accountId, String provider, String identity, String token, Instant expiresAt,
                            Map<String, String> options) {
    }
}
