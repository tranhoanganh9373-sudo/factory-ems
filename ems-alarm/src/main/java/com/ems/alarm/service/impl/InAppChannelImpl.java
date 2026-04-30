package com.ems.alarm.service.impl;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmInbox;
import com.ems.alarm.entity.InboxKind;
import com.ems.alarm.repository.AlarmInboxRepository;
import com.ems.alarm.service.InAppChannel;
import com.ems.auth.entity.User;
import com.ems.auth.repository.UserRepository;
import com.ems.auth.repository.UserRoleRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Component
public class InAppChannelImpl implements InAppChannel {

    private static final Set<String> NOTIFY_ROLES = Set.of("ADMIN", "OPERATOR");

    private final UserRepository users;
    private final UserRoleRepository userRoles;
    private final AlarmInboxRepository inbox;

    public InAppChannelImpl(UserRepository users, UserRoleRepository userRoles, AlarmInboxRepository inbox) {
        this.users = users;
        this.userRoles = userRoles;
        this.inbox = inbox;
    }

    @Override
    @Transactional
    public void sendTriggered(Alarm a) {
        write(a, InboxKind.TRIGGERED);
    }

    @Override
    @Transactional
    public void sendResolved(Alarm a) {
        write(a, InboxKind.RESOLVED);
    }

    private void write(Alarm a, InboxKind kind) {
        List<AlarmInbox> rows = users.findAll().stream()
                .filter(u -> Boolean.TRUE.equals(u.getEnabled()))
                .filter(u -> userRoles.findRoleCodesByUserId(u.getId()).stream().anyMatch(NOTIFY_ROLES::contains))
                .map(u -> {
                    AlarmInbox row = new AlarmInbox();
                    row.setAlarmId(a.getId());
                    row.setUserId(u.getId());
                    row.setKind(kind);
                    return row;
                })
                .toList();
        if (!rows.isEmpty()) {
            inbox.saveAll(rows);
        }
    }
}
