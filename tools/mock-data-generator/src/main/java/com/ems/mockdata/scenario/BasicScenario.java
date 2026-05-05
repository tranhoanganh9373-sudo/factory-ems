package com.ems.mockdata.scenario;

import com.ems.mockdata.production.ProductionEntryGenerator;
import com.ems.mockdata.seed.*;
import com.ems.mockdata.timeseries.InfluxBatchWriter;
import com.ems.mockdata.timeseries.RollupBatchWriter;
import com.ems.mockdata.timeseries.TimeseriesGenerator;
import com.ems.mockdata.verify.SanityChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class BasicScenario implements MockScenario {

    private static final Logger log = LoggerFactory.getLogger(BasicScenario.class);

    private final OrgTreeSeeder orgTreeSeeder;
    private final MeterSeeder meterSeeder;
    private final MeterTopologySeeder meterTopologySeeder;
    private final TariffSeeder tariffSeeder;
    private final ShiftSeeder shiftSeeder;
    private final UserSeeder userSeeder;
    private final TimeseriesGenerator timeseriesGenerator;
    private final InfluxBatchWriter influxBatchWriter;
    private final RollupBatchWriter rollupBatchWriter;
    private final ProductionEntryGenerator productionEntryGenerator;
    private final SanityChecker sanityChecker;

    public BasicScenario(OrgTreeSeeder orgTreeSeeder,
                         MeterSeeder meterSeeder,
                         MeterTopologySeeder meterTopologySeeder,
                         TariffSeeder tariffSeeder,
                         ShiftSeeder shiftSeeder,
                         UserSeeder userSeeder,
                         TimeseriesGenerator timeseriesGenerator,
                         InfluxBatchWriter influxBatchWriter,
                         RollupBatchWriter rollupBatchWriter,
                         ProductionEntryGenerator productionEntryGenerator,
                         SanityChecker sanityChecker) {
        this.orgTreeSeeder = orgTreeSeeder;
        this.meterSeeder = meterSeeder;
        this.meterTopologySeeder = meterTopologySeeder;
        this.tariffSeeder = tariffSeeder;
        this.shiftSeeder = shiftSeeder;
        this.userSeeder = userSeeder;
        this.timeseriesGenerator = timeseriesGenerator;
        this.influxBatchWriter = influxBatchWriter;
        this.rollupBatchWriter = rollupBatchWriter;
        this.productionEntryGenerator = productionEntryGenerator;
        this.sanityChecker = sanityChecker;
    }

    @Override
    public String name() {
        return "basic";
    }

    @Override
    public void seed(ScenarioContext ctx) {
        boolean doMaster = "all".equals(ctx.seedOnly()) || "master".equals(ctx.seedOnly());
        boolean doTimeseries = "all".equals(ctx.seedOnly()) || "timeseries".equals(ctx.seedOnly());

        if (doMaster) {
            log.info("--- Phase B: master data seeding ---");
            orgTreeSeeder.seed();
            meterSeeder.seed(ctx.scale());
            meterTopologySeeder.seed();
            tariffSeeder.seed();
            shiftSeeder.seed();
            userSeeder.seed();
        }

        if (doTimeseries) {
            log.info("--- Phase C/D/E: timeseries generation ---");
            LocalDate endDate = ctx.startDate().plusMonths(ctx.months());
            timeseriesGenerator.generate(ctx.scale(), ctx.startDate(), endDate, ctx.seed(),
                ctx.noInflux(), influxBatchWriter, rollupBatchWriter);
        }

        if (doMaster) {
            log.info("--- Phase F: production entries ---");
            LocalDate endDate = ctx.startDate().plusMonths(ctx.months());
            productionEntryGenerator.generate(ctx.seed(), ctx.startDate(), endDate);
        }

        log.info("--- Phase G: sanity check ---");
        LocalDate endDate = ctx.startDate().plusMonths(ctx.months());
        boolean ok = sanityChecker.check(ctx.startDate(), endDate);
        if (!ok) {
            log.error("Sanity check FAILED");
            System.exit(2);
        }
        log.info("mock-data-generator completed successfully");
    }
}
