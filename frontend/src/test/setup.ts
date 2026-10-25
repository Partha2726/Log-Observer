import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from '@/test/server';

// MSW: unhandled requests fail the test instead of hitting a network.
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const createStorageMock = () => {
  let store: Record<string, string> = {};
  return {
    getItem: (key: string) => store[key] ?? null,
    setItem: (key: string, value: string) => {
      store[key] = value.toString();
    },
    removeItem: (key: string) => {
      delete store[key];
    },
    clear: () => {
      store = {};
    },
    key: (index: number) => Object.keys(store)[index] ?? null,
    get length() {
      return Object.keys(store).length;
    },
  };
};

const storageMock = createStorageMock();
Object.defineProperty(window, 'localStorage', {
  value: storageMock,
  writable: true,
});
Object.defineProperty(globalThis, 'localStorage', {
  value: storageMock,
  writable: true,
});

// React Flow measures nodes with ResizeObserver and reads transforms with DOMMatrixReadOnly,
// neither of which jsdom provides.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
Object.defineProperty(globalThis, 'ResizeObserver', { value: ResizeObserverStub, writable: true });
class DOMMatrixReadOnlyStub {
  m22: number;
  constructor(transform?: string) {
    const scale = transform?.match(/scale\(([0-9.]+)\)/)?.[1];
    this.m22 = scale !== undefined ? Number(scale) : 1;
  }
}
Object.defineProperty(globalThis, 'DOMMatrixReadOnly', { value: DOMMatrixReadOnlyStub, writable: true });

// jsdom has no layout, so no scrolling either; citations call scrollIntoView on the cited line.
Element.prototype.scrollIntoView = function scrollIntoView() {};
