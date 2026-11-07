import { TestBed } from '@angular/core/testing';
import { Amount, formatAmount, toAmount } from './amount';

describe('Amount', () => {
  it('formata em reais com espaço não separável e trata ausência', () => {
    expect(formatAmount(1234.5)).toBe('R$ 1.234,50');
    expect(formatAmount('-10')).toBe('-R$ 10,00');
    expect(formatAmount(null)).toBe('—');
    expect(toAmount('abc')).toBeNull();
  });

  it('formato compacto com 3 algarismos significativos e exato abaixo de mil', () => {
    expect(formatAmount(1_012_345, true)).toBe('R$ 1,01 mi');
    expect(formatAmount(1_250_000, true)).toBe('R$ 1,25 mi');
    expect(formatAmount(5_850, true)).toBe('R$ 5,85 mil');
    expect(formatAmount(-2_345_678, true)).toBe('-R$ 2,35 mi');
    expect(formatAmount(12.34, true)).toBe('R$ 12,34');
  });

  it('valor com sinal: cor acompanhada de sinal explícito', () => {
    const fixture = TestBed.createComponent(Amount);
    fixture.componentRef.setInput('value', 50);
    fixture.componentRef.setInput('signed', true);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent?.trim()).toBe('+R$ 50,00');
    expect(host.classList).toContain('app-amount--positive');
  });

  it('formato compacto mantém o valor exato para leitores de tela', () => {
    const fixture = TestBed.createComponent(Amount);
    fixture.componentRef.setInput('value', 1_250_000);
    fixture.componentRef.setInput('compact', true);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.getAttribute('title')).toBe('R$ 1.250.000,00');
    expect(host.querySelector('.visually-hidden')?.textContent).toContain('1.250.000,00');
  });
});
