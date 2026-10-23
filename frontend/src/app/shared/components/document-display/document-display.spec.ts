import { TestBed } from '@angular/core/testing';
import { DocumentDisplay } from './document-display';

function render(value: string, inputs: Record<string, unknown> = {}) {
  const fixture = TestBed.createComponent(DocumentDisplay);
  fixture.componentRef.setInput('value', value);
  for (const [k, v] of Object.entries(inputs)) {
    fixture.componentRef.setInput(k, v);
  }
  fixture.detectChanges();
  return fixture;
}

describe('DocumentDisplay', () => {
  it('mascara por padrão e identifica o tipo', () => {
    const el = render('52998224725').nativeElement as HTMLElement;
    expect(el.textContent).toContain('CPF');
    expect(el.textContent).toContain('***.982.247-**');
    expect(el.textContent).not.toContain('529.982.247-25');
  });

  it('mantém o valor já mascarado pelo servidor', () => {
    const el = render('**.222.333/0001-**').nativeElement as HTMLElement;
    expect(el.textContent).toContain('CNPJ');
    expect(el.textContent).toContain('**.222.333/0001-**');
  });

  it('exibe completo só quando a tela informa o valor sem máscara', () => {
    const el = render('11222333000181', { masked: false }).nativeElement as HTMLElement;
    expect(el.textContent).toContain('11.222.333/0001-81');
  });

  it('pedido de exibição completa é delegado à tela (acesso registrado no servidor)', () => {
    const fixture = render('52998224725', { revealable: true });
    const reveal = vi.fn();
    fixture.componentInstance.reveal.subscribe(reveal);
    (fixture.nativeElement as HTMLElement).querySelector('button')?.click();
    expect(reveal).toHaveBeenCalled();
  });
});
