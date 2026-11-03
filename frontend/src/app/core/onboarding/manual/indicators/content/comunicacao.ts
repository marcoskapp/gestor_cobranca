import { IndicatorHelp } from '../indicator.models';

/**
 * Indicadores das conversas: tempo de espera e meta (SLA) de primeira resposta e janela de 24 h do
 * WhatsApp. Definições: docs/tecnico/desenho/comunicacao.md §6.1 (SLA) e regras do WhatsApp;
 * cálculo no módulo `communication` (`ResponseTargets`, `InboxSlaScanner`) e em
 * `features/communication/data/communication.rules.ts` (texto da janela).
 */
export const INDICATORS: readonly IndicatorHelp[] = [
  {
    id: 'communication.sla.first-response',
    title: 'Espera e meta de 1ª resposta',
    what: 'Há quanto tempo o devedor espera resposta, com a cor da meta de primeira resposta: dentro do prazo, em aviso ou estourada.',
    how: 'Tempo corrido desde a primeira mensagem do devedor ainda sem resposta. A meta é da assessoria por canal (padrão: WhatsApp e SMS aviso em 15 e limite em 60 minutos; e-mail 4 e 24 horas). A situação vem do servidor, que verifica as conversas a cada minuto.',
    use: 'Responda primeiro as conversas em aviso e estouradas: devedor sem resposta esfria a negociação. Estouros frequentes pedem reforço na equipe da caixa ou mais atendimento pelo assistente.',
    screens: ['communication.conversation', 'communication.inbox'],
  },
  {
    id: 'communication.whatsapp.window',
    title: 'Janela de 24 h do WhatsApp',
    what: 'Período em que é permitido escrever livremente ao devedor no WhatsApp.',
    how: 'Abre a cada mensagem recebida do devedor e fica aberta por 24 horas, regra do próprio WhatsApp. Fora dela, só é possível enviar um template aprovado. E-mail e SMS sempre saem por template.',
    use: 'Aproveite a janela aberta para negociar por texto livre. Quando fechar, use um template para retomar o contato e aguarde a resposta do devedor, que reabre a janela.',
    screens: ['communication.conversation', 'communication.inbox', 'communication.templates'],
  },
];
