import { useState } from 'react';
import { Card, Col, Row } from 'antd';
import { useSearchParams } from 'react-router-dom';
import { useDashboardSearchParams } from '@/stores/dashboardFilter';
import FilterBar from './FilterBar';
import KpiPanel from './KpiPanel';
import RealtimeSeriesPanel from './RealtimeSeriesPanel';
import EnergyCompositionPanel from './EnergyCompositionPanel';
import TopNPanel from './TopNPanel';
import MeterDetailDrawer from './MeterDetailDrawer';

export default function DashboardPage() {
  useDashboardSearchParams();

  const [searchParams, setSearchParams] = useSearchParams();
  const [drawerMeterId, setDrawerMeterId] = useState<number | null>(() => {
    const v = searchParams.get('meterId');
    return v ? Number(v) : null;
  });

  const handleMeterClick = (meterId: number) => {
    setDrawerMeterId(meterId);
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.set('meterId', String(meterId));
        return next;
      },
      { replace: true }
    );
  };

  const handleDrawerClose = () => {
    setDrawerMeterId(null);
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.delete('meterId');
        return next;
      },
      { replace: true }
    );
  };

  return (
    <div style={{ minWidth: 0 }}>
      <FilterBar />

      {/* Row 1: KPI */}
      <Row gutter={[0, 16]} style={{ marginBottom: 16 }}>
        <Col span={24}>
          <Card size="small" bodyStyle={{ padding: 16 }}>
            <KpiPanel />
          </Card>
        </Col>
      </Row>

      {/* Row 2: Realtime curves + Composition */}
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} lg={16}>
          <Card size="small" bodyStyle={{ padding: 16 }}>
            <RealtimeSeriesPanel />
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card size="small" bodyStyle={{ padding: 16 }}>
            <EnergyCompositionPanel />
          </Card>
        </Col>
      </Row>

      {/* Row 3: Top N */}
      <Row>
        <Col span={24}>
          <Card size="small" bodyStyle={{ padding: 16 }}>
            <TopNPanel onMeterClick={handleMeterClick} />
          </Card>
        </Col>
      </Row>

      <MeterDetailDrawer meterId={drawerMeterId} onClose={handleDrawerClose} />
    </div>
  );
}
