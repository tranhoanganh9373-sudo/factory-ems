package com.ems.collector.secret;

import com.ems.collector.cert.OpcUaCertificateLoader;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pkcs12CertificateImporter 单元测试。
 *
 * <p>使用 BouncyCastle 在测试内生成自签 RSA 2048 KeyPair + X.509 cert，
 * 装入 PKCS#12 KeyStore，导出 byte[] 喂给 importer 验证 round-trip。
 */
@DisplayName("Pkcs12CertificateImporter")
class Pkcs12CertificateImporterTest {

    private static final String PASSWORD = "test-pass-123";
    private static final String ALIAS_PRIMARY = "primary";
    private static final String ALIAS_SECONDARY = "secondary";

    private static KeyPair primaryKeyPair;
    private static X509Certificate primaryCert;
    private static KeyPair secondaryKeyPair;
    private static X509Certificate secondaryCert;

    @BeforeAll
    static void setUpKeyMaterial() throws Exception {
        var pair1 = generateSelfSigned("CN=primary");
        primaryKeyPair = pair1.keyPair;
        primaryCert = pair1.cert;

        var pair2 = generateSelfSigned("CN=secondary");
        secondaryKeyPair = pair2.keyPair;
        secondaryCert = pair2.cert;
    }

    @Test
    @DisplayName("有效 PFX + 正确密码 → 返回 PEM，能再被 OpcUaCertificateLoader 解析回原 cert")
    void importPfx_validSingleEntry_roundTripsThroughLoader() throws Exception {
        byte[] pfx = buildKeystore(java.util.List.of(
            new Entry(ALIAS_PRIMARY, primaryKeyPair, primaryCert)
        ));

        var result = Pkcs12CertificateImporter.importPfx(pfx, PASSWORD, null);

        assertThat(result).isNotNull();
        assertThat(result.certificatePem()).contains("-----BEGIN CERTIFICATE-----");
        assertThat(result.encryptedKeyPem()).contains("-----BEGIN ENCRYPTED PRIVATE KEY-----");
        assertThat(result.fingerprintHex()).isNotBlank();

        // round-trip：拼接后由 loader 解析，应得到同一证书
        String pem = result.certificatePem() + "\n" + result.encryptedKeyPem();
        var material = OpcUaCertificateLoader.loadClientKeyMaterial(pem, PASSWORD);

        assertThat(material.certificate()).isEqualTo(primaryCert);
    }

    @Test
    @DisplayName("错误密码 → 抛 IllegalArgumentException 包含 'password'")
    void importPfx_wrongPassword_throws() throws Exception {
        byte[] pfx = buildKeystore(java.util.List.of(
            new Entry(ALIAS_PRIMARY, primaryKeyPair, primaryCert)
        ));

        assertThatThrownBy(() -> Pkcs12CertificateImporter.importPfx(pfx, "wrong", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("password");
    }

    @Test
    @DisplayName("多 entry + null alias → 抛 IllegalArgumentException 包含 'alias'")
    void importPfx_multiEntryNullAlias_throws() throws Exception {
        byte[] pfx = buildKeystore(java.util.List.of(
            new Entry(ALIAS_PRIMARY, primaryKeyPair, primaryCert),
            new Entry(ALIAS_SECONDARY, secondaryKeyPair, secondaryCert)
        ));

        assertThatThrownBy(() -> Pkcs12CertificateImporter.importPfx(pfx, PASSWORD, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("alias");
    }

    @Test
    @DisplayName("多 entry + 有效 alias → 选中对应 entry")
    void importPfx_multiEntryWithAlias_picksCorrectEntry() throws Exception {
        byte[] pfx = buildKeystore(java.util.List.of(
            new Entry(ALIAS_PRIMARY, primaryKeyPair, primaryCert),
            new Entry(ALIAS_SECONDARY, secondaryKeyPair, secondaryCert)
        ));

        var result = Pkcs12CertificateImporter.importPfx(pfx, PASSWORD, ALIAS_SECONDARY);

        String pem = result.certificatePem() + "\n" + result.encryptedKeyPem();
        var material = OpcUaCertificateLoader.loadClientKeyMaterial(pem, PASSWORD);

        assertThat(material.certificate()).isEqualTo(secondaryCert);
    }

    @Test
    @DisplayName("alias 不存在 → 抛 IllegalArgumentException 包含 'alias'")
    void importPfx_aliasNotFound_throws() throws Exception {
        byte[] pfx = buildKeystore(java.util.List.of(
            new Entry(ALIAS_PRIMARY, primaryKeyPair, primaryCert)
        ));

        assertThatThrownBy(() -> Pkcs12CertificateImporter.importPfx(pfx, PASSWORD, "missing"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("alias");
    }

    @Test
    @DisplayName("非法 PFX 字节 → 抛 IllegalArgumentException")
    void importPfx_garbageBytes_throws() {
        assertThatThrownBy(
            () -> Pkcs12CertificateImporter.importPfx("not a pfx".getBytes(), PASSWORD, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- helpers ------------------------------------------------------------

    private record Entry(String alias, KeyPair keyPair, X509Certificate cert) {}

    private static byte[] buildKeystore(java.util.List<Entry> entries) throws Exception {
        var ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        for (Entry e : entries) {
            ks.setKeyEntry(
                e.alias(),
                e.keyPair().getPrivate(),
                PASSWORD.toCharArray(),
                new java.security.cert.Certificate[]{e.cert()});
        }
        try (var out = new ByteArrayOutputStream()) {
            ks.store(out, PASSWORD.toCharArray());
            return out.toByteArray();
        }
    }

    private record GeneratedPair(KeyPair keyPair, X509Certificate cert) {}

    private static GeneratedPair generateSelfSigned(String dn) throws Exception {
        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        var kp = keyGen.generateKeyPair();

        var subject = new X500Name(dn);
        var notBefore = new Date();
        var notAfter = new Date(notBefore.getTime() + 365L * 24 * 3600 * 1000);
        var serial = BigInteger.valueOf(System.nanoTime());

        var builder = new JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, kp.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        var cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        return new GeneratedPair(kp, cert);
    }
}
