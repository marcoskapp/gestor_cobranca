import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { render, setup, tick } from '../../../../../testing/component-harness';
import { contactPersonText } from '../../data/debt.labels';
import { DebtorList } from '../debtor-list/debtor-list';
import { CompanyRepresentatives } from './company-representatives';

const representative = {
  id: 'r-1',
  companyId: 'd-1',
  name: 'João Sócio Fictício',
  maskedDocument: '***.456.789-**',
  role: 'MANAGING_PARTNER',
  canNegotiate: true,
  validUntil: null,
  evidenceNote: 'contrato social de 2024',
  source: 'MANUAL',
  active: true,
  negotiatesToday: true,
  createdAt: '2026-11-01T10:00:00Z',
  endedAt: null,
  endReason: null,
};

describe('Cobrança B2B — ficha e lista', () => {
  it('pessoa de contato aparece mascarada como o valor, com papel e quem decide', () => {
    expect(contactPersonText({ maskedPersonName: 'A***', personRole: 'FINANCE', decisionMaker: true })).toBe('A*** (Financeiro) · decide');
    expect(contactPersonText({ maskedPersonName: 'A***', personRole: 'FINANCE', decisionMaker: false }, 'Ana Fictícia')).toBe('Ana Fictícia (Financeiro)');
    expect(contactPersonText({ maskedPersonName: null, personRole: null, decisionMaker: false })).toBeNull();
  });

  it('representantes: CPF mascarado; sem DEBTOR_MANAGE não há incluir, editar nem encerrar', async () => {
    const { http } = setup(CompanyRepresentatives, ['DEBTOR_VIEW']);
    const fixture = await render(CompanyRepresentatives, http, () => [representative], { companyId: 'd-1' });
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('***.456.789-**');
    expect(text).toContain('Sócio administrador');
    expect(text).toContain('Negocia');
    expect(text).not.toContain('Novo representante');
    expect((fixture.nativeElement as HTMLElement).querySelector('[aria-label^="Encerrar"]')).toBeNull();
    expect((fixture.nativeElement as HTMLElement).querySelector('[aria-label^="Exibir CPF"]')).toBeNull();
  });

  it('com DEBTOR_MANAGE e DEBTOR_SENSITIVE_VIEW aparecem as ações e a revelação', async () => {
    const { http } = setup(CompanyRepresentatives, ['DEBTOR_VIEW', 'DEBTOR_MANAGE', 'DEBTOR_SENSITIVE_VIEW']);
    const fixture = await render(CompanyRepresentatives, http, () => [representative], { companyId: 'd-1' });
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Novo representante');
    expect(el.querySelector('[aria-label^="Encerrar"]')).not.toBeNull();
    expect(el.querySelector('[aria-label^="Exibir CPF"]')).not.toBeNull();
  });

  it('"Outras unidades da empresa" filtra pelo id do devedor de referência, nunca pelo CNPJ', async () => {
    const { http } = setup(DebtorList, ['DEBTOR_VIEW', 'CREDITOR_VIEW', 'PORTFOLIO_VIEW']);
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/devedores?mesmaEmpresa=01a1cce9-19a5-70a7-b6d8-dcef68041ad5');
    const page = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };
    const seen: (string | null)[] = [];
    const respond = (url: string, req: { request: { params: { get(k: string): string | null } } }) => {
      if (url.endsWith('/debtors')) {
        seen.push(req.request.params.get('sameCompanyAs'));
      }
      return page;
    };
    const fixture = await render(DebtorList, http, respond as never);
    await new Promise((r) => setTimeout(r, 400));
    await tick();
    http.match(() => true).forEach((r) => {
      respond(r.request.url, r as never);
      r.flush(page);
    });
    await tick();
    fixture.detectChanges();
    expect(seen).toContain('01a1cce9-19a5-70a7-b6d8-dcef68041ad5');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Outras unidades da empresa');
  });
});
