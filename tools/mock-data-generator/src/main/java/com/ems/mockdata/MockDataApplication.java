package com.ems.mockdata;

import com.ems.mockdata.config.ScaleProfile;
import com.ems.mockdata.scenario.MockScenario;
import com.ems.mockdata.scenario.ScenarioContext;
import com.ems.mockdata.verify.SanityChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@SpringBootApplication(
    scanBasePackages = "com.ems",
    exclude = { SecurityAutoConfiguration.class }
)
@ComponentScan(
    basePackages = "com.ems",
    excludeFilters = {
        // CLI tool — exclude every web/controller layer and the auth security config that needs HttpSecurity
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.ems\\.[^.]+\\.controller\\..*"),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.ems.auth.security.SecurityConfig.class),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.ems\\.app\\.config\\..*")
    }
)
@EntityScan(basePackages = "com.ems")
@EnableJpaRepositories(basePackages = "com.ems")
@EnableTransactionManagement
public class MockDataApplication implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MockDataApplication.class);

    private final List<MockScenario> scenarios;
    private final SanityChecker sanityChecker;
    private final MockDataReseter reseter;

    public MockDataApplication(List<MockScenario> scenarios,
                               SanityChecker sanityChecker,
                               MockDataReseter reseter) {
        this.scenarios = scenarios;
        this.sanityChecker = sanityChecker;
        this.reseter = reseter;
    }

    public static void main(String[] args) {
        // prod-guard: abort before Spring starts if prod profile active
        for (String arg : args) {
            if (arg.contains("prod")) {
                System.err.println("[mock-data] Refusing to run against prod profile. Aborting.");
                System.exit(1);
            }
        }
        String active = System.getenv("SPRING_PROFILES_ACTIVE");
        if (active != null && active.contains("prod")) {
            System.err.println("[mock-data] Refusing to run against prod profile (env). Aborting.");
            System.exit(1);
        }
        SpringApplication.run(MockDataApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ScaleProfile scale = parseScale(args);
        int months = parseInt(args, "months", scale.defaultMonths());
        long seed = parseLong(args, "seed", 42L);
        LocalDate startDate = parseDate(args, "start",
            LocalDate.now().minusMonths(months).withDayOfMonth(1));
        String seedOnly = parseString(args, "seed-only", "all");
        boolean reset = parseBoolean(args, "reset", false);
        boolean noInflux = parseBoolean(args, "no-influx", false);
        boolean verifyOnly = parseBoolean(args, "verify-only", false);
        String scenarioName = parseString(args, "mock.scenario.name", "basic");

        log.info("mock-data-generator starting: scenario={} scale={} months={} seed={} start={} seedOnly={} reset={} noInflux={}",
            scenarioName, scale, months, seed, startDate, seedOnly, reset, noInflux);

        if (verifyOnly) {
            LocalDate endDate = startDate.plusMonths(months);
            boolean ok = sanityChecker.check(startDate, endDate);
            System.exit(ok ? 0 : 2);
            return;
        }

        if (reset) {
            reseter.reset();
        }

        ScenarioContext ctx = new ScenarioContext(scale, months, seed, startDate, seedOnly, reset, noInflux);

        MockScenario chosen = scenarios.stream()
            .filter(s -> s.name().equals(scenarioName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown mock.scenario.name=" + scenarioName +
                "; valid: " + scenarios.stream().map(MockScenario::name).toList()));

        log.info("Selected scenario: {}", chosen.name());
        chosen.seed(ctx);
    }

    private ScaleProfile parseScale(ApplicationArguments args) {
        String v = parseString(args, "scale", "small");
        return switch (v.toLowerCase()) {
            case "medium" -> ScaleProfile.MEDIUM;
            case "large"  -> ScaleProfile.LARGE;
            default       -> ScaleProfile.SMALL;
        };
    }

    private String parseString(ApplicationArguments args, String name, String def) {
        List<String> vals = args.getOptionValues(name);
        return (vals != null && !vals.isEmpty()) ? vals.get(0) : def;
    }

    private int parseInt(ApplicationArguments args, String name, int def) {
        String v = parseString(args, name, null);
        return v != null ? Integer.parseInt(v) : def;
    }

    private long parseLong(ApplicationArguments args, String name, long def) {
        String v = parseString(args, name, null);
        return v != null ? Long.parseLong(v) : def;
    }

    private boolean parseBoolean(ApplicationArguments args, String name, boolean def) {
        String v = parseString(args, name, null);
        if (v == null) return def;
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    private LocalDate parseDate(ApplicationArguments args, String name, LocalDate def) {
        String v = parseString(args, name, null);
        return v != null ? LocalDate.parse(v, DateTimeFormatter.ISO_LOCAL_DATE) : def;
    }
}
