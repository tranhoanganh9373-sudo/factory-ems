package com.ems.collector.cert;

import com.ems.audit.aspect.AuditContext;
import com.ems.audit.event.AuditEvent;
import com.ems.audit.service.AuditService;
import com.ems.collector.runtime.ChannelCertificateApprovedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CertificateApprovalController}.
 *
 * <p>Tests controller logic directly (no Spring context needed).
 * Security annotation (@PreAuthorize) is verified via reflection.
 * Integration-level 403 enforcement is covered by the app's security IT.
 */
class CertificateApprovalControllerTest {

    private OpcUaCertificateStore certStore;
    private AuditService auditService;
    private AuditContext auditContext;
    private ApplicationEventPublisher publisher;
    private CertificateApprovalController controller;

    @BeforeEach
    void setUp() {
        certStore = mock(OpcUaCertificateStore.class);
        auditService = mock(AuditService.class);
        auditContext = mock(AuditContext.class);
        publisher = mock(ApplicationEventPublisher.class);
        controller = new CertificateApprovalController(certStore, auditService, auditContext, publisher);

        when(auditContext.currentUserId()).thenReturn(1L);
        when(auditContext.currentUsername()).thenReturn("admin");
        when(auditContext.currentIp()).thenReturn("127.0.0.1");
        when(auditContext.currentUserAgent()).thenReturn("test-agent");
    }

    // ── Security annotation ───────────────────────────────────────────────────

    @Test
    @DisplayName("Controller class carries @PreAuthorize('hasRole(ADMIN)')")
    void controller_hasPreAuthorizeAdminAnnotation() {
        PreAuthorize annotation = CertificateApprovalController.class.getAnnotation(PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).contains("ADMIN");
    }

    // ── GET /cert-pending ─────────────────────────────────────────────────────

    @Test
    @DisplayName("listPending – returns list from certStore")
    void listPending_returnsListFromStore() throws Exception {
        PendingCertificate pc = new PendingCertificate(
                "aabbccdd", 1L, "opc.tcp://plc.local:4840",
                Instant.parse("2026-04-30T10:00:00Z"), "CN=PLC-Server");
        when(certStore.listPending()).thenReturn(List.of(pc));

        List<PendingCertificate> result = controller.listPending();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).thumbprint()).isEqualTo("aabbccdd");
        assertThat(result.get(0).channelId()).isEqualTo(1L);
        assertThat(result.get(0).endpointUrl()).isEqualTo("opc.tcp://plc.local:4840");
        assertThat(result.get(0).subjectDn()).isEqualTo("CN=PLC-Server");
    }

    @Test
    @DisplayName("listPending – empty store returns empty list")
    void listPending_emptyStore_returnsEmptyList() throws Exception {
        when(certStore.listPending()).thenReturn(List.of());

        List<PendingCertificate> result = controller.listPending();

        assertThat(result).isEmpty();
    }

    // ── POST /{channelId}/trust-cert ──────────────────────────────────────────

    @Test
    @DisplayName("trust – calls certStore.approve, auditService.record, publisher.publishEvent")
    void trust_callsApproveAuditAndEvent() throws Exception {
        var req = new CertificateApprovalController.TrustCertRequest("aabbccdd");

        var response = controller.trust(42L, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(certStore).approve("aabbccdd");
        verify(auditService).record(any(AuditEvent.class));
        verify(publisher).publishEvent(any(ChannelCertificateApprovedEvent.class));
    }

    @Test
    @DisplayName("trust – audit event contains correct action and channelId")
    void trust_auditEventHasCorrectFields() throws Exception {
        var req = new CertificateApprovalController.TrustCertRequest("aabbccdd");
        var captor = ArgumentCaptor.forClass(AuditEvent.class);

        controller.trust(42L, req);

        verify(auditService).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("OPCUA_CERT_TRUST");
        assertThat(event.resourceType()).isEqualTo("CHANNEL");
        assertThat(event.resourceId()).isEqualTo("42");
        assertThat(event.actorUsername()).isEqualTo("admin");
    }

    @Test
    @DisplayName("trust – approved event contains channelId, thumbprint, approvedBy")
    void trust_approvedEventHasCorrectFields() throws Exception {
        var req = new CertificateApprovalController.TrustCertRequest("aabbccdd");
        var captor = ArgumentCaptor.forClass(ChannelCertificateApprovedEvent.class);

        controller.trust(42L, req);

        verify(publisher).publishEvent(captor.capture());
        ChannelCertificateApprovedEvent ev = captor.getValue();
        assertThat(ev.channelId()).isEqualTo(42L);
        assertThat(ev.thumbprint()).isEqualTo("aabbccdd");
        assertThat(ev.approvedBy()).isEqualTo("admin");
    }

    @Test
    @DisplayName("trust – certStore.approve throws IOException – propagates")
    void trust_certStoreThrows_propagates() throws Exception {
        doThrow(new IOException("not found")).when(certStore).approve(any());
        var req = new CertificateApprovalController.TrustCertRequest("aabbccdd");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.trust(42L, req))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not found");
    }

    // ── DELETE /cert-pending/{thumbprint} ─────────────────────────────────────

    @Test
    @DisplayName("reject – calls certStore.reject and auditService.record")
    void reject_callsRejectAndAudit() throws Exception {
        var response = controller.reject("aabbccdd");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(certStore).reject("aabbccdd");
        verify(auditService).record(any(AuditEvent.class));
    }

    @Test
    @DisplayName("reject – audit event contains correct action and thumbprint")
    void reject_auditEventHasCorrectFields() throws Exception {
        var captor = ArgumentCaptor.forClass(AuditEvent.class);

        controller.reject("aabbccdd");

        verify(auditService).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("OPCUA_CERT_REJECT");
        assertThat(event.resourceType()).isEqualTo("CERT");
        assertThat(event.resourceId()).isEqualTo("aabbccdd");
    }

    @Test
    @DisplayName("reject – certStore.reject throws IOException – propagates")
    void reject_certStoreThrows_propagates() throws Exception {
        doThrow(new IOException("not found")).when(certStore).reject(any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.reject("aabbccdd"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not found");
    }
}
