import { IndicatorHelp } from '../indicator.models';

/**
 * Indicadores do compliance: prazo legal das solicitações de titulares (LGPD). Definições:
 * docs/tecnico/seguranca.md (LGPD) e regra em `compliance/domain/service/RequestDeadlines.java`
 * (padrão `app.compliance.requests.default-due-days`).
 */
export const INDICATORS: readonly IndicatorHelp[] = [
  {
    id: 'compliance.requests.deadline',
    title: 'Prazo legal da solicitação',
    what: 'Data limite para responder ao titular dos dados (acesso, correção, eliminação, portabilidade, oposição e outros pedidos da LGPD), com os dias que faltam.',
    how: 'Dias corridos a partir do recebimento ({{compliance.dataSubjectRequestDue}}; a LGPD dá 15 dias para a declaração completa; pode haver prazo próprio por tipo de pedido). O prazo termina ao fim do último dia: recebida em 03/03 com 15 dias, vence ao fim de 18/03.',
    use: 'Atenda primeiro as vencidas e as que vencem em breve; elas também aparecem em Minhas pendências. Perder o prazo expõe a assessoria a sanções da autoridade de proteção de dados.',
    screens: ['compliance.requests', 'compliance.request-detail'],
  },
];
