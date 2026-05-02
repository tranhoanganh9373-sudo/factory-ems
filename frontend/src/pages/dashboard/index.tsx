import { useState } from 'react';
import { Card, Col, Row, Statistic } from 'antd';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useDashboardSearchParams } from '@/stores/dashboardFilter';
import { alarmApi } from '@/api/alarm';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import FilterBar from './FilterBar';
import KpiPanel from './KpiPanel';
import RealtimeSeriesPanel from './RealtimeSeriesPanel';
import EnergyCompositionPanel from './EnergyCompositionPanel';
import TopNPanel from './TopNPanel';
import MeterDetailDrawer from './MeterDetailDrawer';
import TariffDistributionPanel from './TariffDistributionPanel';
import EnergyIntensityPanel from './EnergyIntensityPanel';
import SankeyPanel from './SankeyPanel';
import FloorplanLivePanel from './FloorplanLivePanel';
import CostDistributionPanel from './CostDistributionPanel';
import { DashboardSection } from '@/components/DashboardSection';

export default function DashboardPage() {
  useDashboardSearchParams();
  useDocumentTitle('综合看板');

  const nav = useNavigate();

  const { data: summary } = useQuery({
    queryKey: ['alarms', 'health'],
    queryFn: () => alarmApi.healthSummary(),
    refetchInterval: 30_000,
  });

  const total = (summary?.onlineCount ?? 0) + (summary?.offlineCount ?? 0);
  const onlineRate = total === 0 ? 0 : ((summary?.onlineCount ?? 0) / total) * 100;

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

      {/* Row 0: 采集健康 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} lg={8}>
          <Card
            size="small"
            hoverable
            onClick={() => nav('/alarms/health')}
            title="采集健康"
            bodyStyle={{ padding: 16 }}
          >
            <Row gutter={16}>
              <Col span={12}>
                <Statistic title="在线率" value={onlineRate.toFixed(1)} suffix="%" />
              </Col>
              <Col span={12}>
                <Statistic
                  title="当前告警"
                  value={summary?.alarmCount ?? 0}
                  valueStyle={{ color: (summary?.alarmCount ?? 0) > 0 ? '#ff4d4f' : undefined }}
                />
              </Col>
            </Row>
          </Card>
        </Col>
      </Row>

      {/* Row 1: KPI */}
      <Row gutter={[0, 16]} style={{ marginBottom: 16 }}>
        <Col span={24}>
          <DashboardSection tone="primary">
            <KpiPanel />
          </DashboardSection>
        </Col>
      </Row>

      {/* Row 2: Realtime curves + Composition */}
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} lg={16}>
          <DashboardSection tone="primary">
            <RealtimeSeriesPanel />
          </DashboardSection>
        </Col>
        <Col xs={24} lg={8}>
          <DashboardSection>
            <EnergyCompositionPanel />
          </DashboardSection>
        </Col>
      </Row>

      {/* Row 3: Top N */}
      <Row style={{ marginBottom: 16 }}>
        <Col span={24}>
          <DashboardSection>
            <TopNPanel onMeterClick={handleMeterClick} />
          </DashboardSection>
        </Col>
      </Row>

      {/* Row 4: ⑥ Tariff distribution + ⑦ Energy intensity */}
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} lg={8}>
          <DashboardSection>
            <TariffDistributionPanel />
          </DashboardSection>
        </Col>
        <Col xs={24} lg={16}>
          <DashboardSection>
            <EnergyIntensityPanel />
          </DashboardSection>
        </Col>
      </Row>

      {/* Row 5: ⑧ Sankey + ⑨ Floorplan live */}
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} lg={12}>
          <DashboardSection>
            <SankeyPanel />
          </DashboardSection>
        </Col>
        <Col xs={24} lg={12}>
          <DashboardSection>
            <FloorplanLivePanel />
          </DashboardSection>
        </Col>
      </Row>

      {/* Row 6: ⑩ Cost distribution */}
      <Row gutter={[16, 16]}>
        <Col span={24}>
          <DashboardSection>
            <CostDistributionPanel />
          </DashboardSection>
        </Col>
      </Row>

      <MeterDetailDrawer meterId={drawerMeterId} onClose={handleDrawerClose} />
    </div>
  );
}
