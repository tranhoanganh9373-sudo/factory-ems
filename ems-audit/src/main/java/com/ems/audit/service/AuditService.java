package com.ems.audit.service;

import com.ems.audit.event.AuditEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final ApplicationEventPublisher publisher;

    public AuditService(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void record(AuditEvent event) {
        publisher.publishEvent(event);
    }
}
