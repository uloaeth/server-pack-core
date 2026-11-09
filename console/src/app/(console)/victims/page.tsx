import { Suspense } from 'react';
import { OperationsView } from '@/components/operations-view';
import { SkeletonRows } from '@/components/ui/table';

export default function VictimsPage() {
  return (
    <Suspense fallback={<SkeletonRows rows={16} cols={9} />}>
      <OperationsView />
    </Suspense>
  );
}
