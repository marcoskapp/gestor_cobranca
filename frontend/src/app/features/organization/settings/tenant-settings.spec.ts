import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { render, setup, text, tick } from '../../../../testing/component-harness';
import { ContactNoveltySettings } from '../../debt/data/debt.models';
import { TenantView } from '../data/organization.models';
import { TenantSettings } from './tenant-settings';

const tenant: TenantView = {
  id: 't-1',
  slug: 'aurora',
  legalName: 'Aurora Cobranças Ltda.',
  tradeName: 'Aurora',
  cnpj: '11222333000181',
  status: 'ACTIVE',
  platform: false,
  contactWindowStart: '08:00:00',
  contactWindowEnd: '20:00:00',
  contactOnSaturday: false,
  contactOnSunday: false,
};

const novelty: ContactNoveltySettings = {
  windowDays: 30,
  defaultWindowDays: 30,
  minWindowDays: 1,
  maxWindowDays: 180,
  customized: false,
  updatedAt: null,
};

const respond = (url: string) => (url.endsWith('/debtors/contact-novelty-settings') ? novelty : url.endsWith('/organization/tenant') ? tenant : undefined);

/** Seções por permissão: dados da assessoria (ORG_SETTINGS_MANAGE) e contatos recebidos (DEBTOR_MANAGE). */
describe('TenantSettings', () => {
  it('quem configura a assessoria e edita devedores vê as três seções', async () => {
    const { http } = setup(TenantSettings, ['ORG_SETTINGS_MANAGE', 'DEBTOR_MANAGE']);
    const fixture = await render(TenantSettings, http, respond);
    const content = text(fixture);
    expect(content).toContain('Identificação');
    expect(content).toContain('Dados gerais e janela de contato');
    expect(content).toContain('Contatos recebidos');
    expect(content).toContain('Dados cadastrais, janela de contato com devedores e prazo do selo “Novo” dos contatos recebidos.');
    expect((fixture.nativeElement as HTMLElement).querySelector('#contatos-recebidos')).not.toBeNull();
  });

  it('sem DEBTOR_MANAGE a seção de contatos recebidos fica oculta e não é carregada', async () => {
    const { http } = setup(TenantSettings, ['ORG_SETTINGS_MANAGE']);
    const urls: string[] = [];
    const fixture = await render(TenantSettings, http, (url) => (urls.push(url), respond(url)));
    expect(text(fixture)).toContain('Dados gerais e janela de contato');
    expect(text(fixture)).not.toContain('Contatos recebidos');
    expect(urls.some((u) => u.includes('contact-novelty'))).toBe(false);
  });

  it('só com DEBTOR_MANAGE (supervisor, analista de carteira) vê apenas os contatos recebidos', async () => {
    const { http } = setup(TenantSettings, ['DEBTOR_MANAGE']);
    const urls: string[] = [];
    const fixture = await render(TenantSettings, http, (url) => (urls.push(url), respond(url)));
    const content = text(fixture);
    expect(content).toContain('Contatos recebidos');
    expect(content).toContain('Prazo do selo “Novo” (dias)');
    expect(content).toContain('Prazo do selo “Novo” dos contatos recebidos.');
    expect(content).not.toContain('Identificação');
    expect(content).not.toContain('Razão social');
    expect(urls.some((u) => u.endsWith('/organization/tenant'))).toBe(false);
  });

  it('o link para #contatos-recebidos rola até a seção depois de carregar o que vem antes, uma vez só', async () => {
    const { http } = setup(TenantSettings, ['ORG_SETTINGS_MANAGE', 'DEBTOR_MANAGE']);
    await TestBed.inject(Router).navigateByUrl('/organizacao/configuracoes#contatos-recebidos');
    const scrolled: string[] = [];
    const original = HTMLElement.prototype.scrollIntoView;
    HTMLElement.prototype.scrollIntoView = function (this: HTMLElement) {
      scrolled.push(this.id);
    };
    try {
      const fixture = await render(TenantSettings, http, respond);
      await tick();
      fixture.detectChanges();
      expect(scrolled).toEqual(['contatos-recebidos']);
      fixture.detectChanges();
      await tick();
      expect(scrolled).toEqual(['contatos-recebidos']);
    } finally {
      HTMLElement.prototype.scrollIntoView = original;
    }
  });
});
