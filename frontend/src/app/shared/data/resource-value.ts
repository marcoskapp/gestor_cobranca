import { Resource, Signal, computed } from '@angular/core';

/**
 * Valor de um resource que nunca lança exceção: em estado de erro (quando `value()` lança),
 * retorna o valor de reserva. Use nos templates e em `computed` que dependem de listas auxiliares.
 */
export function valueOr<T>(resource: Resource<T | undefined>, fallback: T): Signal<T> {
  return computed(() => (resource.hasValue() ? (resource.value() as T) : fallback));
}
