import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { AiText } from '@/features/ai/AiText';

describe('AiText', () => {
  it('UI-009: HTML in AI text renders as text, never as markup', () => {
    const { container } = render(
      <AiText text={'<img src=x onerror="alert(1)"> and <b>not bold</b> <script>alert(2)</script> <a href="https://example.test">link</a>'} />,
    );
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('a')).toBeNull();
    expect(container.querySelector('b')).toBeNull();
    expect(container).toHaveTextContent('<img src=x onerror="alert(1)"> and <b>not bold</b>');
  });

  it('honours only lists, bold and citations', () => {
    const onCite = vi.fn();
    const { container } = render(<AiText text={'**Cause**: a timeout [ev:3]\n- check the pool\n- check the query'} onCite={onCite} />);
    expect(container.querySelector('strong')).toHaveTextContent('Cause');
    expect(container.querySelectorAll('li')).toHaveLength(2);
    fireEvent.click(screen.getByRole('button', { name: 'Show evidence ev3' }));
    expect(onCite).toHaveBeenCalledWith('ev3');
  });
});
