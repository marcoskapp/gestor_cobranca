package br.com.gestorcobranca.portal.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Parâmetros do portal de autonegociação ({@code app.portal.*}).
 *
 * @param publicBaseUrl endereço público (https) usado nos links enviados ao devedor
 *                      ({@code <base>/portal/<slug>/entrar#<token>})
 * @param session       sessão do devedor
 * @param access        código de acesso, limites e bloqueio progressivo
 * @param templates     prefixos dos templates do communication (sufixo = canal)
 * @param retention     prazos de descarte dos dados de acesso (LGPD, minimização)
 */
@ConfigurationProperties(prefix = "app.portal")
public record PortalProperties(String publicBaseUrl, Session session, Access access, Templates templates,
                               Retention retention) {

    public PortalProperties {
        publicBaseUrl = publicBaseUrl == null ? "https://localhost:8443" : stripSlash(publicBaseUrl);
        session = session == null ? new Session(null, null, null, null) : session;
        access = access == null ? new Access(null, null, null, null, null, null, null, null, null, null, null, null, null) : access;
        templates = templates == null ? new Templates(null, null, null) : templates;
        retention = retention == null ? new Retention(null, null, null) : retention;
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * @param tokenTtl          validade do token do portal (renovável enquanto a sessão vale)
     * @param idleTimeout       inatividade que encerra a sessão
     * @param absoluteTimeout   duração máxima da sessão
     * @param maxActivePerDebtor sessões simultâneas por devedor (a mais antiga é revogada)
     */
    public record Session(Duration tokenTtl, Duration idleTimeout, Duration absoluteTimeout, Integer maxActivePerDebtor) {

        public Session {
            tokenTtl = tokenTtl == null ? Duration.ofMinutes(10) : tokenTtl;
            idleTimeout = idleTimeout == null ? Duration.ofMinutes(15) : idleTimeout;
            absoluteTimeout = absoluteTimeout == null ? Duration.ofMinutes(60) : absoluteTimeout;
            maxActivePerDebtor = maxActivePerDebtor == null ? 3 : maxActivePerDebtor;
        }
    }

    /**
     * @param codeTtl               validade do código (OTP)
     * @param codeMaxAttempts       tentativas por código
     * @param knowledgeTtl          validade da etapa de conhecimento após o link
     * @param requestsPerMinutePerIp limite por instância e IP nos endpoints públicos (429)
     * @param window                janela de contagem do bloqueio progressivo
     * @param documentMaxFailures   falhas por documento na janela antes do bloqueio
     * @param documentMaxRequests   pedidos de código por documento na janela
     * @param ipMaxFailures         falhas por IP na janela
     * @param ipMaxRequests         pedidos de código por IP na janela
     * @param baseLock              primeiro bloqueio (dobra a cada reincidência)
     * @param maxLock               bloqueio máximo
     * @param minResponseTime       tempo mínimo de resposta do pedido de código (equaliza documento existente e
     *                              inexistente, que não envia mensagem)
     * @param documentMaxCodesPerDay teto de códigos enviados por documento no dia (fuso de operação), na assessoria
     *                               (somando os portais), além do limite por janela: o código está fora da política de
     *                               contato (decisão 1083) e este teto contém o envio repetido a quem não pediu
     */
    public record Access(Duration codeTtl, Integer codeMaxAttempts, Duration knowledgeTtl, Integer requestsPerMinutePerIp,
                         Duration window, Integer documentMaxFailures, Integer documentMaxRequests, Integer ipMaxFailures,
                         Integer ipMaxRequests, Duration baseLock, Duration maxLock, Duration minResponseTime,
                         Integer documentMaxCodesPerDay) {

        public Access {
            documentMaxCodesPerDay = documentMaxCodesPerDay == null ? 15 : documentMaxCodesPerDay;
            if (documentMaxCodesPerDay < 1) {
                throw new IllegalArgumentException("app.portal.access.document-max-codes-per-day deve ser ao menos 1");
            }
            codeTtl = codeTtl == null ? Duration.ofMinutes(10) : codeTtl;
            codeMaxAttempts = codeMaxAttempts == null ? 5 : codeMaxAttempts;
            knowledgeTtl = knowledgeTtl == null ? Duration.ofMinutes(10) : knowledgeTtl;
            requestsPerMinutePerIp = requestsPerMinutePerIp == null ? 20 : requestsPerMinutePerIp;
            window = window == null ? Duration.ofMinutes(30) : window;
            documentMaxFailures = documentMaxFailures == null ? 5 : documentMaxFailures;
            documentMaxRequests = documentMaxRequests == null ? 3 : documentMaxRequests;
            ipMaxFailures = ipMaxFailures == null ? 20 : ipMaxFailures;
            ipMaxRequests = ipMaxRequests == null ? 15 : ipMaxRequests;
            baseLock = baseLock == null ? Duration.ofMinutes(15) : baseLock;
            maxLock = maxLock == null ? Duration.ofHours(24) : maxLock;
            minResponseTime = minResponseTime == null ? Duration.ofMillis(400) : minResponseTime;
        }
    }

    /**
     * Prazos padrão do sistema do descarte dos dados de acesso ao portal (job diário {@code portal.retention}), em
     * dias inteiros. Cada assessoria pode ajustá-los na tela de Retenção (categorias {@code PORTAL_*} do compliance);
     * estes valores valem enquanto ela não configura.
     *
     * @param challenges        desafios de acesso (HMAC do documento digitado, inclusive de quem não é devedor) são
     *                          apagados depois deste prazo do vencimento
     * @param throttles         contadores do bloqueio progressivo (HMAC do documento e do IP) sem uso há este prazo
     *                          e sem bloqueio vigente são apagados
     * @param sessionClientData IP e agente do navegador das sessões encerradas há este prazo (padrão 1 ano) são
     *                          apagados (a sessão continua, sem eles, para o funil e a trilha)
     */
    public record Retention(Duration challenges, Duration throttles, Duration sessionClientData) {

        public Retention {
            challenges = challenges == null ? Duration.ofDays(30) : challenges;
            throttles = throttles == null ? Duration.ofDays(30) : throttles;
            sessionClientData = sessionClientData == null ? Duration.ofDays(365) : sessionClientData;
        }
    }

    /**
     * Códigos de template do communication, com o canal como sufixo (ex.: {@code PORTAL_CODIGO_WHATSAPP}).
     *
     * @param accessCode    variáveis {@code codigo} (CODE) e {@code minutos} (INTEGER)
     * @param accessLink    variáveis {@code link} (URL) e {@code horas} (INTEGER)
     * @param serviceRequest variável {@code protocolo} (CODE)
     */
    public record Templates(String accessCode, String accessLink, String serviceRequest) {

        public Templates {
            accessCode = accessCode == null ? "PORTAL_CODIGO" : accessCode;
            accessLink = accessLink == null ? "PORTAL_LINK" : accessLink;
            serviceRequest = serviceRequest == null ? "PORTAL_ATENDIMENTO" : serviceRequest;
        }
    }
}
