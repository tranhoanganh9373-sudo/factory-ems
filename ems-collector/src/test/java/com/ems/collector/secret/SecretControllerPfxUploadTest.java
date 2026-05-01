package com.ems.collector.secret;

import com.ems.audit.aspect.AuditContext;
import com.ems.audit.event.AuditEvent;
import com.ems.audit.service.AuditService;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SecretController PFX 上传单测——直接构造 controller，不用 Spring context。
 */
@DisplayName("SecretController.uploadPfx")
class SecretControllerPfxUploadTest {

    private static final String PASSWORD = "test-pass-123";

    private static byte[] validPfxBytes;

    private SecretResolver resolver;
    private AuditService auditService;
    private AuditContext auditContext;
    private SecretController controller;

    @BeforeAll
    static void buildPfx() throws Exception {
        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair kp = keyGen.generateKeyPair();

        X509Certificate cert = generateSelfSigned("CN=upload-test", kp);

        var ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("primary", kp.getPrivate(), PASSWORD.toCharArray(),
            new java.security.cert.Certificate[]{cert});
        try (var out = new ByteArrayOutputStream()) {
            ks.store(out, PASSWORD.toCharArray());
            validPfxBytes = out.toByteArray();
        }
    }

    @BeforeEach
    void setUp() {
        resolver = mock(SecretResolver.class);
        auditService = mock(AuditService.class);
        auditContext = mock(AuditContext.class);
        when(auditContext.currentUserId()).thenReturn(1L);
        when(auditContext.currentUsername()).thenReturn("admin");
        when(auditContext.currentIp()).thenReturn("127.0.0.1");
        when(auditContext.currentUserAgent()).thenReturn("test-agent");

        controller = new SecretController(resolver, auditService, auditContext);
    }

    @Test
    @DisplayName("成功上传 → 204 且 resolver.write 调用两次（pem + password）")
    void uploadPfx_success_writesPemAndPassword() {
        MultipartFile file = new MockMultipartFile("file", "client.pfx",
            "application/octet-stream", validPfxBytes);

        var resp = controller.uploadPfx(file, "client", PASSWORD, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var refCaptor = ArgumentCaptor.forClass(String.class);
        var valCaptor = ArgumentCaptor.forClass(String.class);
        verify(resolver, org.mockito.Mockito.times(2))
            .write(refCaptor.capture(), valCaptor.capture());

        assertThat(refCaptor.getAllValues())
            .containsExactly("secret://opcua/client.pem", "secret://opcua/client.pem.password");
        assertThat(valCaptor.getAllValues().get(0))
            .contains("-----BEGIN CERTIFICATE-----")
            .contains("-----BEGIN ENCRYPTED PRIVATE KEY-----");
        assertThat(valCaptor.getAllValues().get(1)).isEqualTo(PASSWORD);

        verify(auditService).record(any(AuditEvent.class));
    }

    @Test
    @DisplayName("成功上传 → 审计事件 action=SECRET_PFX_UPLOAD targetId=secret://opcua/<name>.pem")
    void uploadPfx_success_recordsAuditEvent() {
        MultipartFile file = new MockMultipartFile("file", "client.pfx",
            "application/octet-stream", validPfxBytes);

        controller.uploadPfx(file, "client", PASSWORD, null);

        var captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("SECRET_PFX_UPLOAD");
        assertThat(event.resourceType()).isEqualTo("SECRET");
        assertThat(event.resourceId()).isEqualTo("secret://opcua/client.pem");
    }

    @Test
    @DisplayName("空文件 → 抛 IllegalArgumentException")
    void uploadPfx_emptyFile_throws() {
        MultipartFile file = new MockMultipartFile("file", "client.pfx",
            "application/octet-stream", new byte[0]);

        assertThatThrownBy(() -> controller.uploadPfx(file, "client", PASSWORD, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("> 64KB 文件 → 抛 IllegalArgumentException")
    void uploadPfx_oversizedFile_throws() {
        byte[] big = new byte[65_537];
        MultipartFile file = new MockMultipartFile("file", "client.pfx",
            "application/octet-stream", big);

        assertThatThrownBy(() -> controller.uploadPfx(file, "client", PASSWORD, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("非法 name '../etc/passwd' → 抛 IllegalArgumentException")
    void uploadPfx_pathTraversalName_throws() {
        MultipartFile file = new MockMultipartFile("file", "client.pfx",
            "application/octet-stream", validPfxBytes);

        assertThatThrownBy(() -> controller.uploadPfx(file, "../etc/passwd", PASSWORD, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- helpers ------------------------------------------------------------

    private static X509Certificate generateSelfSigned(String dn, KeyPair kp) throws Exception {
        var subject = new X500Name(dn);
        var notBefore = new Date();
        var notAfter = new Date(notBefore.getTime() + 365L * 24 * 3600 * 1000);
        var serial = BigInteger.valueOf(System.nanoTime());

        var builder = new JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, kp.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }
}
