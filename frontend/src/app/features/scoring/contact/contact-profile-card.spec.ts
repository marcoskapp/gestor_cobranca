import { TestBed } from '@angular/core/testing';
import { button, render, setup, text, tick } from '../../../../testing/component-harness';
import { ContactProfileView } from '../data/contact-intelligence.models';
import { ContactProfileCard } from './contact-profile-card';

const URL = '/api/v1/scoring/debtors/d-1/contact-profile';

function profile(overrides: Partial<ContactProfileView> = {}): ContactProfileView {
  return {
    debtorId: 'd-1',
    caseId: 'k-1',
    totalAttempts: 14,
    minAttempts: 5,
    sufficient: true,
    emptyReason: null,
    preferredChannel: 'WHATSAPP',
    preferredChannelExplanation: 'Prefere WhatsApp: 3 respostas em 4 mensagens, 2 iniciadas pelo devedor.',
    channels: [],
    contacts: [
      {
        contactId: 'ct-2',
        channel: 'WHATSAPP',
        attempts: 4,
        successes: 3,
        debtorInitiated: 2,
        reads: 4,
        rate: 0.66,
        confidence: 'LOW',
        bestSlot: null,
        attemptsToFirstSuccess: 1,
        lastSuccessAt: '2026-11-02T13:00:00Z',
        explanation: '3 de 4 mensagens respondidas.',
        bestSlotExplanation: null,
      },
      {
        contactId: 'ct-1',
        channel: 'PHONE',
        attempts: 10,
        successes: 6,
        debtorInitiated: 0,
        reads: 0,
        rate: 0.58,
        confidence: 'HIGH',
        bestSlot: { startHour: 18, endHour: 21, attempts: 5, successes: 4, rate: 0.7 },
        attemptsToFirstSuccess: 3,
        lastSuccessAt: '2026-11-05T21:30:00Z',
        explanation: '6 de 10 ligações atendidas (taxa ajustada 58%).',
        bestSlotExplanation: 'Melhor horário: 18h–21h (4 de 5 ligações atendidas).',
      },
      {
        contactId: 'ct-gone',
        channel: 'PHONE',
        attempts: 5,
        successes: 0,
        debtorInitiated: 0,
        reads: 0,
        rate: 0.1,
        confidence: 'MEDIUM',
        bestSlot: null,
        attemptsToFirstSuccess: null,
        lastSuccessAt: null,
        explanation: '0 de 5 ligações atendidas.',
        bestSlotExplanation: null,
      },
    ],
    portfolioMedianAttemptsToFirstSuccess: 4,
    notice: 'Orientação para o contato: não decide nem bloqueia o atendimento.',
    zone: 'America/Sao_Paulo',
    ...overrides,
  };
}

const CONTACTS = [
  { contactId: 'ct-1', type: 'PHONE', maskedValue: '(11) *****-1234', label: 'MOBILE', whatsapp: false },
  { contactId: 'ct-2', type: 'PHONE', maskedValue: '(11) *****-9876', label: 'MOBILE', whatsapp: true },
];

describe('ContactProfileCard', () => {
  it('não aparece nem consulta a API sem SCORING_VIEW', async () => {
    const h = setup(ContactProfileCard, ['CASE_WORK']);
    const f = await render(ContactProfileCard, h.http, () => ({}), { debtorId: 'd-1', caseId: 'k-1', contacts: CONTACTS });
    expect(text(f).trim()).toBe('');
    h.http.verify();
  });

  it('carregando: avisa e marca a seção como ocupada', async () => {
    const h = setup(ContactProfileCard, ['SCORING_VIEW']);
    const f = TestBed.createComponent(ContactProfileCard);
    f.componentRef.setInput('debtorId', 'd-1');
    f.detectChanges();
    await tick();
    f.detectChanges();
    expect(text(f)).toContain('Carregando o perfil de contato');
    expect((f.nativeElement as HTMLElement).querySelector('section')!.getAttribute('aria-busy')).toBe('true');
    const req = h.http.expectOne((r) => r.url === URL);
    // sem caso: o perfil é do devedor inteiro
    expect(req.request.params.has('caseId')).toBe(false);
    req.flush(profile());
  });

  it('canal preferido e contatos na ordem do servidor, com o valor mascarado da tela; confiança baixa = dados insuficientes', async () => {
    const h = setup(ContactProfileCard, ['SCORING_VIEW']);
    const f = await render(
      ContactProfileCard,
      h.http,
      (url, req) => {
        if (url === URL) {
          expect(req.request.params.get('caseId')).toBe('k-1');
          return profile();
        }
        return undefined;
      },
      { debtorId: 'd-1', caseId: 'k-1', contacts: CONTACTS },
    );
    const t = text(f);
    expect(t).toContain('WhatsApp');
    expect(t).toContain('Prefere WhatsApp: 3 respostas em 4 mensagens');
    const items = [...(f.nativeElement as HTMLElement).querySelectorAll('li.cp__item')].map((li) => li.textContent ?? '');
    expect(items).toHaveLength(3);
    expect(items[0]).toContain('(11) *****-9876');
    expect(items[0]).toContain('Dados insuficientes');
    expect(items[0]).not.toContain('66%');
    expect(items[1]).toContain('(11) *****-1234');
    expect(items[1]).toContain('58%');
    expect(items[1]).toContain('Confiança alta');
    expect(items[1]).toContain('Melhor horário: 18h–21h');
    expect(items[1]).toContain('Tentativas até o 1º sucesso: 3');
    expect(items[1]).toContain('05/11/2026');
    expect(items[2]).toContain('Contato removido');
    expect(items[2]).toContain('ainda sem sucesso');
    expect(t).toContain('mediana é de 4 tentativas');
    expect(t).toContain('não decide nem bloqueia');
  });

  it('dados insuficientes: mostra o motivo do servidor e o aviso de finalidade', async () => {
    const h = setup(ContactProfileCard, ['SCORING_VIEW']);
    const f = await render(
      ContactProfileCard,
      h.http,
      (url) => (url === URL ? profile({ sufficient: false, totalAttempts: 2, emptyReason: 'Ainda são 2 tentativas; o perfil aparece a partir de 5.', contacts: [] }) : undefined),
      { debtorId: 'd-1', contacts: CONTACTS },
    );
    expect(text(f)).toContain('Ainda são 2 tentativas; o perfil aparece a partir de 5.');
    expect(text(f)).toContain('não decide nem bloqueia');
    expect((f.nativeElement as HTMLElement).querySelector('li')).toBeNull();
  });

  it('erro: mensagem com "Tentar novamente", que consulta de novo', async () => {
    const h = setup(ContactProfileCard, ['SCORING_VIEW']);
    const f = TestBed.createComponent(ContactProfileCard);
    f.componentRef.setInput('debtorId', 'd-1');
    f.detectChanges();
    await tick();
    h.http.expectOne((r) => r.url === URL).flush({ code: 'boom' }, { status: 500, statusText: 'Server Error' });
    await tick();
    f.detectChanges();
    expect(text(f)).toContain('Não foi possível carregar o perfil de contato.');
    expect((f.nativeElement as HTMLElement).querySelector('[role="alert"]')).not.toBeNull();
    button(f, 'Tentar novamente')!.click();
    f.detectChanges();
    await tick();
    h.http.expectOne((r) => r.url === URL).flush(profile());
    await tick();
    f.detectChanges();
    expect(text(f)).toContain('Prefere WhatsApp');
  });

  it('fora do escopo (404): o cartão não aparece', async () => {
    const h = setup(ContactProfileCard, ['SCORING_VIEW']);
    const f = TestBed.createComponent(ContactProfileCard);
    f.componentRef.setInput('debtorId', 'd-1');
    f.detectChanges();
    await tick();
    h.http.expectOne((r) => r.url === URL).flush({ code: 'not_found' }, { status: 404, statusText: 'Not Found' });
    await tick();
    f.detectChanges();
    expect(text(f).trim()).toBe('');
  });
});
