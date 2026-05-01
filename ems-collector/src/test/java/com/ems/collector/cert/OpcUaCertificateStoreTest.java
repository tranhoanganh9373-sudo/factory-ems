package com.ems.collector.cert;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // ── new method tests ──────────────────────────────────────────────────────

    @Test
    @DisplayName("init 同时创建 pending 和 rejected 目录")
    void init_createsPendingAndRejectedDirectories() {
        assertThat(secretsDir.resolve("opcua/certs/pending")).exists().isDirectory();
        assertThat(secretsDir.resolve("opcua/certs/rejected")).exists().isDirectory();
    }

    @Test
    @DisplayName("addPending 写入 .der 和 .json 到 pending 目录")
    void addPending_writesFilesToPendingDir() throws Exception {
        var cert = generateSelfSignedCert("CN=PLC-Server");
        var thumb = store.thumbprint(cert);

        store.addPending(cert, 42L, "opc.tcp://plc.example.com:4840");

        var pendingDir = secretsDir.resolve("opcua/certs/pending");
        assertThat(pendingDir.resolve(thumb + ".der")).exists();
        assertThat(pendingDir.resolve(thumb + ".json")).exists();
    }

    @Test
    @DisplayName("addPending 幂等：第二次调用不覆盖已有文件")
    void addPending_idempotent_skipsSecondWrite() throws Exception {
        var cert = generateSelfSignedCert("CN=PLC-Server");
        var thumb = store.thumbprint(cert);
        var pendingDir = secretsDir.resolve("opcua/certs/pending");

        store.addPending(cert, 42L, "opc.tcp://plc.example.com:4840");
        var firstWriteTime = Files.getLastModifiedTime(pendingDir.resolve(thumb + ".der"));

        // small sleep to ensure mtime would differ if file were rewritten
        Thread.sleep(10);
        store.addPending(cert, 99L, "opc.tcp://other:4840");

        var secondWriteTime = Files.getLastModifiedTime(pendingDir.resolve(thumb + ".der"));
        assertThat(secondWriteTime).isEqualTo(firstWriteTime);
    }

    @Test
    @DisplayName("listPending 返回正确的元数据")
    void listPending_returnsCorrectMetadata() throws Exception {
        var cert = generateSelfSignedCert("CN=PLC-Server");
        var thumb = store.thumbprint(cert);

        store.addPending(cert, 7L, "opc.tcp://plc.local:4840");

        List<PendingCertificate> list = store.listPending();

        assertThat(list).hasSize(1);
        PendingCertificate pc = list.get(0);
        assertThat(pc.thumbprint()).isEqualTo(thumb);
        assertThat(pc.channelId()).isEqualTo(7L);
        assertThat(pc.endpointUrl()).isEqualTo("opc.tcp://plc.local:4840");
        assertThat(pc.firstSeenAt()).isNotNull();
        assertThat(pc.subjectDn()).contains("PLC-Server");
    }

    @Test
    @DisplayName("approve(thumbprint) 移动 .der 到 trusted，删除 .json")
    void approveByThumbprint_movesFileToTrusted() throws Exception {
        var cert = generateSelfSignedCert("CN=PLC-Server");
        var thumb = store.thumbprint(cert);

        store.addPending(cert, 1L, "opc.tcp://plc.local:4840");
        store.approve(thumb);

        assertThat(secretsDir.resolve("opcua/certs/pending/" + thumb + ".der")).doesNotExist();
        assertThat(secretsDir.resolve("opcua/certs/pending/" + thumb + ".json")).doesNotExist();
        assertThat(secretsDir.resolve("opcua/certs/trusted/" + thumb + ".der")).exists();
        assertThat(store.isTrusted(cert)).isTrue();
    }

    @Test
    @DisplayName("reject(thumbprint) 移动 .der 到 rejected，删除 .json")
    void rejectByThumbprint_movesFileToRejected() throws Exception {
        var cert = generateSelfSignedCert("CN=PLC-Server");
        var thumb = store.thumbprint(cert);

        store.addPending(cert, 1L, "opc.tcp://plc.local:4840");
        store.reject(thumb);

        assertThat(secretsDir.resolve("opcua/certs/pending/" + thumb + ".der")).doesNotExist();
        assertThat(secretsDir.resolve("opcua/certs/pending/" + thumb + ".json")).doesNotExist();
        assertThat(secretsDir.resolve("opcua/certs/rejected/" + thumb + ".der")).exists();
    }

    @Test
    @DisplayName("approve(thumbprint) 不存在时抛 IOException")
    void approveByThumbprint_notFound_throws() {
        assertThatThrownBy(() -> store.approve("nonexistent"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    @DisplayName("reject(thumbprint) 不存在时抛 IOException")
    void rejectByThumbprint_notFound_throws() {
        assertThatThrownBy(() -> store.reject("nonexistent"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @DisplayName("addPending 文件权限 rw------- (POSIX only)")
    void addPending_filePermissions_ownerRwOnly() throws Exception {
        var cert = generateSelfSignedCert("CN=PLC-Server");
        var thumb = store.thumbprint(cert);
        store.addPending(cert, 1L, "opc.tcp://plc.local:4840");

        Set<PosixFilePermission> derPerms = Files.getPosixFilePermissions(
                secretsDir.resolve("opcua/certs/pending/" + thumb + ".der"));
        assertThat(derPerms).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

        Set<PosixFilePermission> jsonPerms = Files.getPosixFilePermissions(
                secretsDir.resolve("opcua/certs/pending/" + thumb + ".json"));
        assertThat(jsonPerms).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
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
