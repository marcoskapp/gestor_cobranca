import { ContactPointProfile } from './contact-intelligence.models';
import {
  agreementRows,
  attemptsLabel,
  bestHeatCell,
  channelRows,
  contactRows,
  defaultInsightsRange,
  firstSuccessRows,
  heatmapRows,
  insightsRangeProblem,
  medianText,
  rateOrDash,
  ratePercent,
  slotLabel,
} from './contact-intelligence.rules';

const profile = (overrides: Partial<ContactPointProfile> = {}): ContactPointProfile => ({
  contactId: 'ct-1',
  channel: 'PHONE',
  attempts: 10,
  successes: 6,
  debtorInitiated: 0,
  reads: 0,
  rate: 0.58,
  confidence: 'MEDIUM',
  bestSlot: null,
  attemptsToFirstSuccess: 2,
  lastSuccessAt: '2026-11-01T14:00:00Z',
  explanation: '6 de 10 ligações atendidas (taxa ajustada 58%).',
  bestSlotExplanation: null,
  ...overrides,
});

describe('regras da inteligência de contato', () => {
  it('faixa horária, porcentagens e medianas em português', () => {
    expect(slotLabel(6)).toBe('06h–09h');
    expect(slotLabel(18, 21)).toBe('18h–21h');
    expect(ratePercent(0.583)).toBe('58%');
    expect(ratePercent(null)).toBe('—');
    expect(rateOrDash(0.4, 9, 10)).toBe('—');
    expect(rateOrDash(0.4, 10, 10)).toBe('40,0%');
    expect(medianText(3.5, 'tentativas')).toBe('3,5 tentativas');
    expect(medianText(null, 'tentativas')).toBe('—');
    expect(attemptsLabel(1)).toBe('1 tentativa');
    expect(attemptsLabel(11)).toBe('11 ou mais');
  });

  it('período: padrão de 90 dias, vazio, invertido e acima de 400 dias', () => {
    expect(defaultInsightsRange('2026-11-11')).toEqual({ from: '2026-08-13', to: '2026-11-11' });
    expect(insightsRangeProblem(null, '2026-11-11')).toContain('Informe');
    expect(insightsRangeProblem('2026-11-11', '2026-11-01')).toContain('posterior');
    expect(insightsRangeProblem('2025-01-01', '2026-11-11')).toBe('Período máximo de 400 dias.');
    expect(insightsRangeProblem('2025-10-07', '2026-11-11')).toBeNull();
  });

  it('junta o perfil com os contatos da tela pelo id (valor mascarado), na ordem do servidor', () => {
    const rows = contactRows(
      [profile({ contactId: 'ct-2', channel: 'WHATSAPP', rate: 0.7 }), profile(), profile({ contactId: 'ct-9' }), profile({ contactId: null, confidence: 'LOW' })],
      [
        { contactId: 'ct-1', type: 'PHONE', maskedValue: '(11) *****-1234', label: 'MOBILE', whatsapp: false },
        { id: 'ct-2', type: 'PHONE', maskedValue: '(11) *****-9876', label: 'WORK', whatsapp: true },
      ],
    );
    expect(rows.map((r) => r.value)).toEqual(['(11) *****-9876', '(11) *****-1234', 'Contato removido', 'Contato não identificado']);
    expect(rows[0].kind).toBe('Telefone · Comercial · WhatsApp');
    expect(rows[2].known).toBe(false);
    expect(rows[3].insufficient).toBe(true);
    expect(rows[0].insufficient).toBe(false);
  });

  it('mapa de calor: matriz completa, "—" abaixo do mínimo e intensidade relativa à maior taxa', () => {
    const rows = heatmapRows(
      [
        { weekday: 1, startHour: 9, attempts: 20, successes: 10, rate: 0.5 },
        { weekday: 1, startHour: 18, attempts: 20, successes: 5, rate: 0.25 },
        { weekday: 7, startHour: 21, attempts: 3, successes: 3, rate: 1 },
      ],
      10,
    );
    expect(rows).toHaveLength(7);
    expect(rows[0].cells).toHaveLength(6);
    const mon9 = rows[0].cells.find((c) => c.startHour === 9)!;
    expect(mon9.text).toBe('50,0%');
    expect(mon9.intensity).toBe(100);
    expect(rows[0].cells.find((c) => c.startHour === 18)!.intensity).toBe(50);
    const sun21 = rows[6].cells.find((c) => c.startHour === 21)!;
    expect(sun21.text).toBe('—');
    expect(sun21.description).toContain('poucas tentativas');
    expect(bestHeatCell(rows)).toMatchObject({ day: 'Segunda-feira', startHour: 9 });
    expect(bestHeatCell(heatmapRows([], 10))).toBeNull();
  });

  it('linhas dos gráficos: curva, canais e acordos por faixa de tentativas', () => {
    const curve = firstSuccessRows([
      { attempts: 11, contacts: 4, cumulativeShare: 1 },
      { attempts: 1, contacts: 30, cumulativeShare: 0.6 },
    ]);
    expect(curve.map((r) => r.label)).toEqual(['1', '11+']);
    expect(curve[0].note).toBe('1 tentativa · 60,0% acumulado');

    const channels = channelRows(
      [
        { channel: 'EMAIL', attempts: 5, successes: 1, rate: 0.2, debtorInitiated: 0, reads: 2 },
        { channel: 'PHONE', attempts: 40, successes: 10, rate: 0.25, debtorInitiated: 0, reads: 0 },
      ],
      10,
    );
    expect(channels.map((r) => r.label)).toEqual(['Ligação', 'E-mail']);
    expect(channels[0].display).toEqual(['25,0%']);
    expect(channels[1].display).toEqual(['—']);
    expect(channels[1].values).toEqual([0]);

    const agreements = agreementRows([
      { bucket: '4-6', agreements: 10, completed: 6, broken: 2, active: 1, cancelled: 1, fulfillmentRate: 0.75 },
      { bucket: '0-1', agreements: 3, completed: 1, broken: 1, active: 1, cancelled: 0, fulfillmentRate: 0.5 },
    ]);
    expect(agreements.map((r) => r.label)).toEqual(['Até 1 tentativa', '4 a 6 tentativas']);
    expect(agreements[1].values).toEqual([6, 2]);
    expect(agreements[1].note).toBe('10 acordos · cumprimento 75,0%');
  });
});
