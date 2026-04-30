package com.ems.alarm.service;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmInbox;
import com.ems.alarm.entity.InboxKind;
import com.ems.alarm.repository.AlarmInboxRepository;
import com.ems.alarm.service.impl.InAppChannelImpl;
import com.ems.auth.entity.User;
import com.ems.auth.repository.UserRepository;
import com.ems.auth.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InAppChannelTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UserRoleRepository userRoles = mock(UserRoleRepository.class);
    private final AlarmInboxRepository inbox = mock(AlarmInboxRepository.class);
    private final InAppChannelImpl channel = new InAppChannelImpl(users, userRoles, inbox);

    @Test
    void sendTriggered_writesOneRowPerEligibleUser() {
        User u1 = user(1L, true);   // ADMIN, enabled
        User u2 = user(2L, true);   // OPERATOR, enabled
        User u3 = user(3L, true);   // VIEWER, enabled (skip)
        User u4 = user(4L, false);  // ADMIN, disabled (skip)
        when(users.findAll()).thenReturn(List.of(u1, u2, u3, u4));
        when(userRoles.findRoleCodesByUserId(1L)).thenReturn(List.of("ADMIN"));
        when(userRoles.findRoleCodesByUserId(2L)).thenReturn(List.of("OPERATOR"));
        when(userRoles.findRoleCodesByUserId(3L)).thenReturn(List.of("VIEWER"));
        when(userRoles.findRoleCodesByUserId(4L)).thenReturn(List.of("ADMIN"));

        Alarm a = newAlarm(99L);
        channel.sendTriggered(a);

        ArgumentCaptor<List<AlarmInbox>> cap = ArgumentCaptor.forClass(List.class);
        verify(inbox).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(2);
        assertThat(cap.getValue()).allMatch(r -> r.getKind() == InboxKind.TRIGGERED);
        assertThat(cap.getValue()).allMatch(r -> r.getAlarmId().equals(99L));
        assertThat(cap.getValue().stream().map(AlarmInbox::getUserId).toList()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void sendResolved_writesKindResolved() {
        User u1 = user(1L, true);
        when(users.findAll()).thenReturn(List.of(u1));
        when(userRoles.findRoleCodesByUserId(1L)).thenReturn(List.of("ADMIN"));

        Alarm a = newAlarm(99L);
        channel.sendResolved(a);

        ArgumentCaptor<List<AlarmInbox>> cap = ArgumentCaptor.forClass(List.class);
        verify(inbox).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        assertThat(cap.getValue().get(0).getKind()).isEqualTo(InboxKind.RESOLVED);
    }

    @Test
    void noEligibleUsers_writesNothing() {
        when(users.findAll()).thenReturn(List.of(user(5L, true)));
        when(userRoles.findRoleCodesByUserId(5L)).thenReturn(List.of("VIEWER"));

        channel.sendTriggered(newAlarm(99L));

        verify(inbox, never()).saveAll(anyList());
    }

    private User user(Long id, boolean enabled) {
        User u = new User();
        u.setId(id);
        u.setEnabled(enabled);
        return u;
    }

    private Alarm newAlarm(Long id) {
        Alarm a = new Alarm();
        a.setId(id);
        return a;
    }
}
