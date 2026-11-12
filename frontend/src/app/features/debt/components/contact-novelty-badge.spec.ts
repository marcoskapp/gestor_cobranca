import { TestBed } from '@angular/core/testing';
import { contactNoveltyBadge, formatReceiptDate } from '../data/contact-novelty';
import { ContactReceiptView } from '../data/debt.models';
import { ContactNoveltyBadge } from './contact-novelty-badge';

const receipt = (overrides: Partial<ContactReceiptView> = {}): ContactReceiptView => ({
  novelty: 'NEW',
  receivedAt: '2026-11-10',
  noveltyUntil: '2026-12-10',
  confirmedAt: null,
  firstAttemptAt: null,
  ...overrides,
});

describe('selo de contato recebido', () => {
  it('novo sem tentativa: "Novo" com a data da carga', () => {
    expect(contactNoveltyBadge(receipt())).toEqual({ label: 'Novo', hint: 'Recebido na carga de 10/11/2026; ainda sem tentativa.' });
  });

  it('depois da primeira tentativa: atendeu ou sem resposta, com a data do teste', () => {
    expect(contactNoveltyBadge(receipt({ novelty: 'NEW_ANSWERED', firstAttemptAt: '2026-11-12' }))).toEqual({
      label: 'Novo · atendeu',
      hint: 'Recebido na carga de 10/11/2026; já testado em 12/11/2026 (atendeu).',
    });
    expect(contactNoveltyBadge(receipt({ novelty: 'NEW_NO_ANSWER', firstAttemptAt: '2026-11-12' }))?.label).toBe('Novo · sem resposta');
  });

  it('reenvio pelo credor: acrescenta a confirmação e, sem novidade, só a dica', () => {
    expect(contactNoveltyBadge(receipt({ confirmedAt: '2026-11-20' }))?.hint).toBe(
      'Recebido na carga de 10/11/2026; ainda sem tentativa. Confirmado pelo credor em 20/11/2026.',
    );
    expect(contactNoveltyBadge(receipt({ novelty: null, confirmedAt: '2026-11-20' }))).toEqual({
      label: null,
      hint: 'Confirmado pelo credor em 20/11/2026.',
    });
  });

  it('sem recebimento ou sem nada a mostrar: nenhum selo', () => {
    expect(contactNoveltyBadge(null)).toBeNull();
    expect(contactNoveltyBadge(receipt({ novelty: null }))).toBeNull();
    expect(formatReceiptDate(null)).toBe('');
  });

  it('renderiza ícone, texto e a dica para o leitor de tela', async () => {
    TestBed.configureTestingModule({ imports: [ContactNoveltyBadge] });
    const fixture = TestBed.createComponent(ContactNoveltyBadge);
    fixture.componentRef.setInput('receipt', receipt());
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('app-icon')).not.toBeNull();
    expect(el.textContent).toContain('Novo');
    expect(el.querySelector('.visually-hidden')?.textContent).toContain('ainda sem tentativa');

    fixture.componentRef.setInput('receipt', null);
    fixture.detectChanges();
    expect(el.textContent?.trim()).toBe('');
  });
});
