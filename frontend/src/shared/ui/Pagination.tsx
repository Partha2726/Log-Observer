import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Button } from '@/shared/ui/Button';

/** Page controls; a total that is a lower bound (Elasticsearch caps at 10,000) shows as "10,000+". */
export function Pagination({ page, totalPages, totalElements, lowerBound, onPage }: {
  page: number;
  totalPages: number;
  totalElements: number;
  lowerBound: boolean;
  onPage: (page: number) => void;
}) {
  return (
    <div className="flex items-center justify-between border-t border-[var(--border-hairline)] px-4 py-2 text-xs text-ink-secondary">
      <span>
        {totalElements.toLocaleString('en-US')}
        {lowerBound ? '+' : ''} results
      </span>
      <div className="flex items-center gap-2">
        <Button variant="ghost" onClick={() => onPage(page - 1)} disabled={page <= 0} aria-label="Previous page">
          <ChevronLeft className="h-4 w-4" aria-hidden />
        </Button>
        <span>
          Page {page + 1} of {Math.max(totalPages, 1)}
        </span>
        <Button variant="ghost" onClick={() => onPage(page + 1)} disabled={page + 1 >= totalPages} aria-label="Next page">
          <ChevronRight className="h-4 w-4" aria-hidden />
        </Button>
      </div>
    </div>
  );
}
