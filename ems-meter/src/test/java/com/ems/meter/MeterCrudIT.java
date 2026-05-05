package com.ems.meter;

import com.ems.audit.aspect.AuditContext;
import com.ems.meter.dto.BindParentMeterReq;
import com.ems.meter.dto.CreateMeterReq;
import com.ems.meter.dto.UpdateMeterReq;
import com.ems.meter.entity.EnergyType;
import com.ems.meter.repository.EnergyTypeRepository;
import com.ems.meter.repository.MeterRepository;
import com.ems.meter.repository.MeterTopologyRepository;
import com.ems.meter.service.EnergyTypeService;
import com.ems.meter.service.MeterService;
import com.ems.meter.service.MeterTopologyService;
import com.ems.orgtree.entity.OrgNode;
import com.ems.orgtree.repository.OrgNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = MeterCrudIT.TestApp.class)
@Testcontainers
@ActiveProfiles("test")
class MeterCrudIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MeterService meterService;
    @Autowired MeterTopologyService topologyService;
    @Autowired EnergyTypeService energyTypeService;
    @Autowired EnergyTypeRepository energyTypes;
    @Autowired OrgNodeRepository orgNodes;
    @Autowired MeterRepository meters;
    @Autowired MeterTopologyRepository topology;

    Long elecId;
    Long workshopId;

    @BeforeEach
    void cleanAndSeed() {
        topology.deleteAll();
        meters.deleteAll();
        orgNodes.deleteAll();

        OrgNode plant = new OrgNode();
        plant.setName("工厂"); plant.setCode("P-IT-" + System.nanoTime());
        plant.setNodeType("PLANT"); plant.setSortOrder(0);
        plant = orgNodes.save(plant);
        workshopId = plant.getId();

        elecId = energyTypes.findByCode("ELEC").map(EnergyType::getId).orElseThrow();
    }

    @Test
    void create_list_get_works() {
        var dto = meterService.create(new CreateMeterReq(
            "M-IT-1", "电表 1", elecId, workshopId, true, null, null, null, null, null, null));

        assertThat(dto.id()).isNotNull();
        assertThat(dto.energyTypeCode()).isEqualTo("ELEC");
        assertThat(dto.unit()).isEqualTo("kWh");

        assertThat(meterService.list(workshopId, elecId, true)).hasSize(1);

        var fetched = meterService.getById(dto.id());
        assertThat(fetched.code()).isEqualTo("M-IT-1");
        // 三字段已被 service 强制写入约定值
        assertThat(fetched.influxTagKey()).isEqualTo("meter_code");
        assertThat(fetched.influxTagValue()).isEqualTo("M-IT-1");
    }

    @Test
    void duplicateCode_isRejected() {
        meterService.create(new CreateMeterReq(
            "M-IT-2", "x", elecId, workshopId, true, null, null, null, null, null, null));
        assertThatThrownBy(() -> meterService.create(new CreateMeterReq(
            "M-IT-2", "y", elecId, workshopId, true, null, null, null, null, null, null)))
            .hasMessageContaining("测点编码已存在");
    }

    @Test
    void update_changesFieldsAndIncrementsVersion() {
        var created = meterService.create(new CreateMeterReq(
            "M-IT-4", "old", elecId, workshopId, true, null, null, null, null, null, null));
        var v0 = meters.findById(created.id()).orElseThrow().getVersion();

        meterService.update(created.id(), new UpdateMeterReq(
            "M-IT-4", "new", elecId, workshopId, false, null, null, null, null, null, null));

        var after = meters.findById(created.id()).orElseThrow();
        assertThat(after.getName()).isEqualTo("new");
        assertThat(after.getEnabled()).isFalse();
        assertThat(after.getVersion()).isGreaterThan(v0);
    }

    @Test
    void update_changeCode_renamesTagValue() {
        var created = meterService.create(new CreateMeterReq(
            "M-IT-OLD", "x", elecId, workshopId, true, null, null, null, null, null, null));

        meterService.update(created.id(), new UpdateMeterReq(
            "M-IT-NEW", "x", elecId, workshopId, true, null, null, null, null, null, null));

        var after = meters.findById(created.id()).orElseThrow();
        assertThat(after.getCode()).isEqualTo("M-IT-NEW");
        assertThat(after.getInfluxTagValue()).isEqualTo("M-IT-NEW");
    }

    @Test
    void bindAndUnbindTopology_works() {
        var parent = meterService.create(new CreateMeterReq(
            "P-IT", "总表", elecId, workshopId, true, null, null, null, null, null, null));
        var child = meterService.create(new CreateMeterReq(
            "C-IT", "分表", elecId, workshopId, true, null, null, null, null, null, null));

        topologyService.bind(child.id(), new BindParentMeterReq(parent.id()));
        assertThat(meterService.getById(child.id()).parentMeterId()).isEqualTo(parent.id());

        topologyService.unbind(child.id());
        assertThat(meterService.getById(child.id()).parentMeterId()).isNull();
    }

    @Test
    void bindCycle_isRejected() {
        var a = meterService.create(new CreateMeterReq(
            "A-IT", "A", elecId, workshopId, true, null, null, null, null, null, null));
        var b = meterService.create(new CreateMeterReq(
            "B-IT", "B", elecId, workshopId, true, null, null, null, null, null, null));
        topologyService.bind(b.id(), new BindParentMeterReq(a.id()));   // a -> b
        // Now binding a's parent to b would cycle.
        assertThatThrownBy(() -> topologyService.bind(a.id(), new BindParentMeterReq(b.id())))
            .hasMessageContaining("环");
    }

    @Test
    void deleteParentWithChildren_isRejected() {
        var parent = meterService.create(new CreateMeterReq(
            "P2-IT", "总", elecId, workshopId, true, null, null, null, null, null, null));
        var child = meterService.create(new CreateMeterReq(
            "C2-IT", "分", elecId, workshopId, true, null, null, null, null, null, null));
        topologyService.bind(child.id(), new BindParentMeterReq(parent.id()));

        assertThatThrownBy(() -> meterService.delete(parent.id()))
            .hasMessageContaining("子测点");
    }

    @Test
    void energyTypes_listReturnsSeed() {
        assertThat(energyTypeService.list())
            .extracting(t -> t.code())
            .containsExactly("ELEC", "WATER", "STEAM");
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackages = {"com.ems.meter.entity", "com.ems.orgtree.entity"})
    @EnableJpaRepositories(basePackages = {"com.ems.meter.repository", "com.ems.orgtree.repository"})
    @ComponentScan(basePackages = {"com.ems.meter.service"})
    static class TestApp {
        @Bean AuditContext ctx() {
            return new AuditContext() {
                public Long currentUserId() { return 1L; }
                public String currentUsername() { return "tester"; }
                public String currentIp() { return "127.0.0.1"; }
                public String currentUserAgent() { return "it"; }
            };
        }
    }
}
