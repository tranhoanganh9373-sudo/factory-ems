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
    @DisplayName("reset() issues each DELETE in MockDataReseter.RESET_SQL exactly, in order")
    void reset_emitsRunbookSqlSequence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var reseter = new MockDataReseter(jdbc);

        reseter.reset();

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(MockDataReseter.RESET_SQL.size())).execute(sqlCaptor.capture());
        // Single source of truth: production constant. Drift in either direction
        // (typo, reorder, missing/extra entry) fails this assertion immediately.
        assertThat(sqlCaptor.getAllValues()).containsExactlyElementsOf(MockDataReseter.RESET_SQL);
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
