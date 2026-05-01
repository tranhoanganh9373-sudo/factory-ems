package com.ems.mockdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("MockDataReseter")
class MockDataReseterTest {

    @Test
    @DisplayName("reset() issues DELETEs in FK-safe order matching the runbook")
    void reset_emitsRunbookSqlSequence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var reseter = new MockDataReseter(jdbc);

        reseter.reset();

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(13)).execute(sqlCaptor.capture());
        List<String> issued = sqlCaptor.getAllValues();

        // Order matters — children before parents to satisfy FK constraints.
        assertThat(issued.get(0)).isEqualToIgnoringCase("DELETE FROM production_entries");
        assertThat(issued.get(1)).isEqualToIgnoringCase("DELETE FROM ts_rollup_monthly");
        assertThat(issued.get(2)).isEqualToIgnoringCase("DELETE FROM ts_rollup_daily");
        assertThat(issued.get(3)).isEqualToIgnoringCase("DELETE FROM ts_rollup_hourly");
        assertThat(issued.get(4)).isEqualToIgnoringCase("DELETE FROM meter_topology");
        assertThat(issued.get(5)).containsIgnoringCase("DELETE FROM meters")
                                  .containsIgnoringCase("MOCK-");
        assertThat(issued.get(6)).isEqualToIgnoringCase("DELETE FROM tariff_periods");
        assertThat(issued.get(7)).containsIgnoringCase("DELETE FROM tariff_plans")
                                  .containsIgnoringCase("MOCK-");
        assertThat(issued.get(8)).containsIgnoringCase("DELETE FROM shifts")
                                  .containsIgnoringCase("MOCK-");
        // closure children before org_nodes parents
        assertThat(issued.get(9)).containsIgnoringCase("DELETE FROM org_node_closure");
        assertThat(issued.get(10)).containsIgnoringCase("DELETE FROM org_nodes")
                                   .containsIgnoringCase("MOCK-");
        // user_roles before users (FK)
        assertThat(issued.get(11)).containsIgnoringCase("DELETE FROM user_roles");
        assertThat(issued.get(12)).containsIgnoringCase("DELETE FROM users")
                                   .containsIgnoringCase("mock-");
    }

    @Test
    @DisplayName("reset() targets only MOCK- prefixed master rows (no scorched-earth on real data)")
    void reset_neverTouchesNonMockPrefixedTables() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var reseter = new MockDataReseter(jdbc);

        reseter.reset();

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(13)).execute(sqlCaptor.capture());
        List<String> issued = sqlCaptor.getAllValues();

        assertThat(issued.stream().filter(s -> s.toLowerCase().contains("delete from meters")).findFirst())
                .hasValueSatisfying(s -> assertThat(s).containsIgnoringCase("MOCK-"));
        assertThat(issued.stream().filter(s -> s.toLowerCase().contains("delete from users")).findFirst())
                .hasValueSatisfying(s -> assertThat(s).containsIgnoringCase("mock-"));
        assertThat(issued.stream().filter(s -> s.toLowerCase().contains("delete from org_nodes")).findFirst())
                .hasValueSatisfying(s -> assertThat(s).containsIgnoringCase("MOCK-"));
        assertThat(issued.stream().filter(s -> s.toLowerCase().contains("delete from tariff_plans")).findFirst())
                .hasValueSatisfying(s -> assertThat(s).containsIgnoringCase("MOCK-"));
        assertThat(issued.stream().filter(s -> s.toLowerCase().contains("delete from shifts")).findFirst())
                .hasValueSatisfying(s -> assertThat(s).containsIgnoringCase("MOCK-"));
    }
}
