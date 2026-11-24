package br.com.gestorcobranca.portal.domain.service;

/**
 * Chave do contador de limite do portal do devedor: documento e IP no acesso; devedor PJ ({@code REP_DEBTOR}) e IP
 * ({@code REP_IP}) na confirmação do representante (cobrança B2B), contadas à parte do acesso. A regra de limite é
 * {@code shared.security.ThrottlePolicy}.
 */
public enum ThrottleKeyType { DOCUMENT, IP, REP_DEBTOR, REP_IP }
