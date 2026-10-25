import { apiValue, toDatetimeLocal } from '../components/form-dialog';
import { viabilityProblems } from '../matters/viability-dialog';
import { ruleCriteria } from '../setup/eligibility-rules';
import { parseAlertLevels } from '../setup/legal-settings';
import { ViabilityResultView } from './legal.models';
import { addDays, budgetUsage, cnjValid, deadlineText, deadlineTone, formatCnj, isFinalStage } from './legal.rules';

describe('regras do jurídico', () => {
  it('número CNJ com dígito verificador mod 97', () => {
    expect(cnjValid('0001234-56.2026.8.26.0100')).toBe(cnjValid('00012345620268260100'));
    // número válido gerado pela fórmula: DD = 98 − (NNNNNNN AAAA J TR OOOO × 100 mod 97)
    const base = '0001234' + '2026' + '8' + '26' + '0100';
    let rest = 0;
    for (const ch of base + '00') {
      rest = (rest * 10 + Number(ch)) % 97;
    }
    const dd = String(98 - rest).padStart(2, '0');
    const valid = `0001234${dd}20268260100`;
    expect(cnjValid(valid)).toBe(true);
    expect(formatCnj(valid)).toBe(`0001234-${dd}.2026.8.26.0100`);
    const wrong = `0001234${dd === '00' ? '01' : '00'}20268260100`;
    expect(cnjValid(wrong)).toBe(false);
    expect(cnjValid('123')).toBe(false);
  });

  it('urgência dos prazos pelos dias úteis restantes', () => {
    const today = '2026-10-23';
    expect(deadlineTone({ status: 'PENDING', dueDate: '2026-10-30', businessDaysRemaining: 5 }, today)).toBe('soon');
    expect(deadlineTone({ status: 'PENDING', dueDate: '2026-11-30', businessDaysRemaining: 20 }, today)).toBe('ok');
    expect(deadlineTone({ status: 'PENDING', dueDate: '2026-10-22', businessDaysRemaining: null }, today)).toBe('late');
    expect(deadlineTone({ status: 'MISSED', dueDate: '2026-10-20', businessDaysRemaining: null }, today)).toBe('late');
    expect(deadlineTone({ status: 'MET', dueDate: '2026-10-20', businessDaysRemaining: null }, today)).toBe('done');
    expect(deadlineText({ status: 'PENDING', dueDate: today, businessDaysRemaining: 0 }, today)).toBe('Vence hoje');
    expect(deadlineText({ status: 'PENDING', dueDate: '2026-10-26', businessDaysRemaining: 1 }, today)).toBe('1 dia útil');
    expect(deadlineText({ status: 'MISSED', dueDate: '2026-10-20', businessDaysRemaining: null }, today)).toBe('Prazo perdido');
  });

  it('uso do orçamento, fases finais e datas', () => {
    expect(budgetUsage({ authorizedBudget: 1000, expensesTotal: 250 })).toBe(25);
    expect(budgetUsage({ authorizedBudget: null, expensesTotal: 250 })).toBeNull();
    expect(isFinalStage('CLOSED')).toBe(true);
    expect(isFinalStage('FILED')).toBe(false);
    expect(addDays('2026-12-28', 10)).toBe('2027-01-07');
  });

  it('conclusão da viabilidade segue as exigências do domínio', () => {
    const result = { availableRoutes: ['PROTEST'], economicallyViable: false } as unknown as ViabilityResultView;
    expect(viabilityProblems(true, [], [], '', null)).toEqual(['Calcule a viabilidade antes de registrar a análise.']);
    expect(viabilityProblems(true, ['EXECUTION'], [{ code: 'c', label: 'Contrato', status: 'MISSING', required: true }], '', result)).toEqual([
      'Há via recomendada que não está disponível para este caso.',
      'Há documento obrigatório faltando no checklist.',
      'Custo maior que o benefício: registre o parecer que justifica seguir.',
    ]);
    expect(viabilityProblems(true, ['PROTEST'], [], 'Seguir pelo protesto', result)).toEqual([]);
    expect(viabilityProblems(false, [], [], '', result)).toEqual(['Para concluir como inviável, registre o parecer.']);
  });

  it('critérios da regra em texto e níveis de alerta', () => {
    expect(
      ruleCriteria({ minDaysOverdue: 180, minOpenBalance: 5000, requireCollateral: true, requireExecutiveTitle: false, minBrokenAgreements: null, prescriptionWithinDays: 90 }),
    ).toBe('atraso ≥ 180 dias e saldo ≥ R$\u00a05.000,00 e com garantia e prescrição em até 90 dias');
    expect(parseAlertLevels('2, 5;0')).toEqual([5, 2, 0]);
    expect(parseAlertLevels('5, 31')).toBeNull();
    expect(parseAlertLevels('')).toBeNull();
  });

  it('formulário converte valores para o contrato da API', () => {
    // o ngbDatepicker entrega LocalDate; texto não reconhecido (erro ngbDate) não vai para a API
    expect(apiValue({ key: 'd', label: 'D', type: 'date' }, '2026-10-23')).toBe('2026-10-23');
    expect(apiValue({ key: 'd', label: 'D', type: 'date' }, '31/02/2026')).toBeNull();
    expect(apiValue({ key: 'm', label: 'M', type: 'money' }, '12.5')).toBe(12.5);
    expect(apiValue({ key: 'm', label: 'M', type: 'money' }, '')).toBeNull();
    expect(apiValue({ key: 'e', label: 'E', type: 'entity' }, ['c-1'])).toBe('c-1');
    expect(apiValue({ key: 't', label: 'T', type: 'text' }, '  ')).toBeNull();
    expect(apiValue({ key: 't', label: 'T', type: 'text' }, ' ok ')).toBe('ok');
    const local = toDatetimeLocal(new Date(2026, 9, 23, 14, 30));
    expect(local).toBe('2026-10-23T14:30');
    expect(apiValue({ key: 'h', label: 'H', type: 'datetime' }, local)).toBe(new Date(2026, 9, 23, 14, 30).toISOString());
  });
});
