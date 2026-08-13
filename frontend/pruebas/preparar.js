import '@testing-library/jest-dom/vitest'

import { afterEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

/**
 * requestAnimationFrame se deja como lo trae jsdom (que en el entorno de vitest
 * viene con pretendToBeVisual y por tanto lo implementa de verdad).
 *
 * Reemplazarlo por una version sincrona parecia inofensivo y no lo era: el
 * encadenado del foco de la carga inicial pide el frame siguiente justo para que
 * la fila nueva ya exista en el DOM. Resolverlo de inmediato lo ejecutaria antes
 * de que React monte la fila, y el foco caeria en la fila anterior — es decir, la
 * prueba estaria midiendo el shim y no la pantalla.
 */
