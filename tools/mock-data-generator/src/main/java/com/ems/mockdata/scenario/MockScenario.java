package com.ems.mockdata.scenario;

public interface MockScenario {
    /** Name matched by --mock.scenario.name=<name>. */
    String name();
    /** Run the scenario end-to-end. Idempotent: skip work if already seeded. */
    void seed(ScenarioContext ctx);
}
