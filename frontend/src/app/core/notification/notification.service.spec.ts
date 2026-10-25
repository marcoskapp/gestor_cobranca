import { TestBed } from '@angular/core/testing';
import { NgbConfig } from '@ng-bootstrap/ng-bootstrap';
import { NotificationService } from './notification.service';
import { Toasts } from './toasts';

describe('NotificationService (toasts)', () => {
  beforeEach(() => {
    TestBed.inject(NgbConfig).animation = false;
  });

  it('erros são assertivos e ficam mais tempo; sucesso é polido', () => {
    const service = TestBed.inject(NotificationService);
    service.success('Credor salvo.');
    service.error('Não foi possível salvar.');
    const [ok, fail] = service.notifications();
    expect(ok).toMatchObject({ tone: 'success', delay: 5000 });
    expect(fail).toMatchObject({ tone: 'error', delay: 10000 });

    const fixture = TestBed.createComponent(Toasts);
    fixture.detectChanges();
    const toasts = (fixture.nativeElement as HTMLElement).querySelectorAll('ngb-toast');
    expect(toasts.length).toBe(2);
    expect(toasts[0].getAttribute('aria-live')).toBe('polite');
    expect(toasts[1].getAttribute('aria-live')).toBe('assertive');
    expect(toasts[1].textContent).toContain('Não foi possível salvar.');
  });

  it('não empilha a mesma mensagem e pode ser fechada', () => {
    const service = TestBed.inject(NotificationService);
    service.error('Sem conexão.');
    service.error('Sem conexão.');
    expect(service.notifications().length).toBe(1);

    const fixture = TestBed.createComponent(Toasts);
    fixture.detectChanges();
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[aria-label="Fechar"]')?.click();
    expect(service.notifications().length).toBe(0);
  });
});
