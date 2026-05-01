package com.ems.collector.cert;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpcUaCertificateStore")
class OpcUaCertificateStoreTest {

    @TempDir Path secretsDir;
    OpcUaCertificateStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new OpcUaCertificateStore(secretsDir);
        store.init();
    }

    @Test
    @DisplayName("init 创建 trusted 目录")
    void init_createsTrustedDirectory() {
        assertThat(secretsDir.resolve("opcua/certs/trusted")).exists().isDirectory();
    }

    @Test
    @DisplayName("isTrusted 返回 false 对未审批证书")
    void isTrusted_unapproved_returnsFalse() throws Exception {
        var cert = generateSelfSignedCert("CN=Test");
        assertThat(store.isTrusted(cert)).isFalse();
    }

    @Test
    @DisplayName("approve 后 isTrusted 返回 true")
    void isTrusted_afterApprove_returnsTrue() throws Exception {
        var cert = generateSelfSignedCert("CN=Test");
        store.approve(cert, "demo-server");
        assertThat(store.isTrusted(cert)).isTrue();
    }

    @Test
    @DisplayName("approve 写入文件名包含 displayName 和 thumbprint")
    void approve_writesFileNamedByDisplayNameAndThumbprint() throws Exception {
        var cert = generateSelfSignedCert("CN=Test");
        var thumb = store.thumbprint(cert);

        store.approve(cert, "factory-plc");
        var trustedDir = secretsDir.resolve("opcua/certs/trusted");
        try (var stream = Files.list(trustedDir)) {
            assertThat(stream.map(p -> p.getFileName().toString()))
                .anyMatch(n -> n.equals("factory-plc-" + thumb + ".der"));
        }
    }

    @Test
    @DisplayName("approve displayName=null 使用默认名 cert")
    void approve_nullDisplayName_usesDefaultPrefix() throws Exception {
        var cert = generateSelfSignedCert("CN=Test");
        var thumb = store.thumbprint(cert);
        store.approve(cert, null);
        var trustedDir = secretsDir.resolve("opcua/certs/trusted");
        try (var stream = Files.list(trustedDir)) {
            assertThat(stream.map(p -> p.getFileName().toString()))
                .anyMatch(n -> n.equals("cert-" + thumb + ".der"));
        }
    }

    @Test
    @DisplayName("thumbprint 是 SHA-256 hex（64 字符全小写）")
    void thumbprint_isSha256Hex() throws Exception {
        var cert = generateSelfSignedCert("CN=Test");
        var t = store.thumbprint(cert);
        assertThat(t).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("不同 CN 证书 thumbprint 不同")
    void thumbprint_differentCerts_differentHashes() throws Exception {
        var c1 = generateSelfSignedCert("CN=Server-A");
        var c2 = generateSelfSignedCert("CN=Server-B");
        assertThat(store.thumbprint(c1)).isNotEqualTo(store.thumbprint(c2));
    }

    /** 生成自签 X509 证书（仅测试用），通过 BouncyCastle JCA provider 构建。 */
    private X509Certificate generateSelfSignedCert(String dn) throws Exception {
        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        var kp = keyGen.generateKeyPair();

        var subject = new X500Name(dn);
        var notBefore = new Date();
        var notAfter = new Date(notBefore.getTime() + 365L * 24 * 3600 * 1000);
        var serial = BigInteger.valueOf(System.nanoTime());

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, kp.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }
}
