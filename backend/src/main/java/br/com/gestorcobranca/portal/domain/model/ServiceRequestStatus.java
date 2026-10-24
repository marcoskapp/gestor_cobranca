package br.com.gestorcobranca.portal.domain.model;

/**
 * {@code OPEN}: registrado, aguardando o encaminhamento; {@code FORWARDED}: conversa aberta/transferida
 * para a fila humana; {@code UNDELIVERED}: não foi possível abrir a conversa (sem contato, template ou
 * canal) — fica na lista de pedidos do portal para a operação.
 */
public enum ServiceRequestStatus { OPEN, FORWARDED, UNDELIVERED }
