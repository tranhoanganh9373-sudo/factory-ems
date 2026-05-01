package com.ems.collector.transport.impl;

import com.ems.collector.cert.OpcUaCertificateStore;
import com.ems.collector.protocol.OpcUaConfig;
import com.ems.collector.protocol.OpcUaPoint;
import com.ems.collector.protocol.SecurityMode;
import com.ems.collector.protocol.SubscriptionMode;
import com.ems.collector.secret.SecretResolver;
import com.ems.collector.transport.Sample;
import com.ems.collector.transport.SampleSink;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.identity.AnonymousIdentityValidator;
import org.eclipse.milo.opcua.stack.core.security.DefaultCertificateManager;
import org.eclipse.milo.opcua.stack.core.security.DefaultTrustListManager;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration;
import org.eclipse.milo.opcua.stack.server.security.ServerCertificateValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.math.BigInteger;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * OpcUaTransportIT — in-process Milo server 端到端测试（SecurityMode SIGN）。
 *
 * <p>启动一个 Milo OPC UA server（Basic256Sha256 / Sign），client 用自签证书连接，
 * 读取 ns=0;i=2258（ServerStatus.CurrentTime），断言 Sample 非 null。
 *
 * <p>不依赖 Docker；命名为 *IT.java 与项目集成测试惯例一致。
 */
@DisplayName("OpcUaTransportIT (in-process Milo SIGN)")
class OpcUaTransportIT {

    private OpcUaServer server;
    private int serverPort;
    private OpcUaCertificateStore certStore;
    private SecretResolver secretResolver;

    /** ns=0;i=2258 = Server > ServerStatus > CurrentTime (DateTime). */
    private static final String SERVER_STATUS_CURRENT_TIME = "ns=0;i=2258";

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        serverPort = findFreePort();

        // 1. 生成 server 和 client 自签名证书（RSA 2048，带 SAN URI）
        var serverKp = generateKeyPair();
        var serverCert = buildSelfSignedCert(
            "CN=ems-test-server",
            "urn:ems:server:test",
            "localhost",
            serverKp);

        var clientKp = generateKeyPair();
        var clientCert = buildSelfSignedCert(
            "CN=ems-test-client",
            "urn:ems:collector",
            null,
            clientKp);

        // 2. 构造 server PKI 目录（DefaultTrustListManager 要求）
        var pkiDir = tempDir.resolve("server-pki").toFile();
        pkiDir.mkdirs();
        var trustListManager = new DefaultTrustListManager(pkiDir);

        // 3. 把 server cert 写入 tempDir/opcua/certs/trusted（让 OpcUaCertificateStore 信任）
        certStore = new OpcUaCertificateStore(tempDir);
        certStore.init();
        certStore.approve(serverCert, "ems-test-server");

        // 4. 启动 OpcUaServer in-process（Basic256Sha256 / Sign）
        var certManager = new DefaultCertificateManager(serverKp, serverCert);
        // 测试用 accept-all 验证器：server 端无需做严格 PKIX 验证（测试关注点是 SIGN 握手）。
        var certValidator = new ServerCertificateValidator() {
            @Override
            public void validateCertificateChain(List<X509Certificate> chain) {}
            @Override
            public void validateCertificateChain(List<X509Certificate> chain, String appUri) {}
        };

        // OPC UA 要求 server 暴露一个 None/None endpoint 以支持 GetEndpoints 发现，
        // 即使实际通信用的是 SIGN endpoint。
        var discoveryEndpoint = EndpointConfiguration.newBuilder()
            .setBindAddress("0.0.0.0")
            .setHostname("localhost")
            .setPath("/ems-test")
            .setBindPort(serverPort)
            .setSecurityPolicy(SecurityPolicy.None)
            .setSecurityMode(MessageSecurityMode.None)
            .build();

        var signEndpoint = EndpointConfiguration.newBuilder()
            .setBindAddress("0.0.0.0")
            .setHostname("localhost")
            .setPath("/ems-test")
            .setBindPort(serverPort)
            .setSecurityPolicy(SecurityPolicy.Basic256Sha256)
            .setSecurityMode(MessageSecurityMode.Sign)
            .setCertificate(serverCert)
            .build();

        var serverConfig = OpcUaServerConfig.builder()
            .setApplicationName(LocalizedText.english("EMS Test Server"))
            .setApplicationUri("urn:ems:server:test")
            .setProductUri("urn:ems:server:test")
            .setCertificateManager(certManager)
            .setTrustListManager(trustListManager)
            .setCertificateValidator(certValidator)
            .setIdentityValidator(AnonymousIdentityValidator.INSTANCE)
            .setEndpoints(Set.of(discoveryEndpoint, signEndpoint))
            .build();

        server = new OpcUaServer(serverConfig);
        server.startup().get(10, TimeUnit.SECONDS);

        // 5. 构造 SecretResolver mock：将 "client-cert-ref" 解析为 client PEM（cert + PKCS8 key）
        String clientPem = toPem(clientCert) + toPkcs8Pem(clientKp);
        secretResolver = Mockito.mock(SecretResolver.class);
        Mockito.when(secretResolver.resolve("client-cert-ref")).thenReturn(clientPem);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.shutdown().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("SIGN 模式端到端：client 连接、读取 ServerStatus.CurrentTime、收到 Sample")
    void signMode_clientConnects_readsServerStatusAndReceivesSample() throws Exception {
        // Arrange
        var collected = new ConcurrentLinkedQueue<Sample>();
        SampleSink sink = collected::offer;

        var cfg = new OpcUaConfig(
            "opc.tcp://localhost:" + serverPort + "/ems-test",
            SecurityMode.SIGN,
            "client-cert-ref",
            null,          // certPasswordRef（无密码）
            null, null,    // usernameRef / passwordRef
            Duration.ofMillis(500),
            List.of(new OpcUaPoint("currentTime", SERVER_STATUS_CURRENT_TIME,
                SubscriptionMode.READ, null, null)));

        var transport = new OpcUaTransport(secretResolver, certStore);

        try {
            // Act
            transport.start(99L, cfg, sink);

            // Assert — 等待最多 15 秒收到一个 Sample（DateTime 非 null 即证明 SIGN 链路通）
            await().atMost(15, TimeUnit.SECONDS)
                .until(() -> !collected.isEmpty());

            var sample = collected.poll();
            assertThat(sample).isNotNull();
            assertThat(sample.channelId()).isEqualTo(99L);
            assertThat(sample.pointKey()).isEqualTo("currentTime");
            assertThat(sample.value()).isNotNull();

        } finally {
            transport.stop();
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private static int findFreePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static KeyPair generateKeyPair() throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    /**
     * 用 BouncyCastle 生成带 SAN 的自签名证书。
     *
     * @param dn     Distinguished Name，如 "CN=ems-test-server"
     * @param sanUri SAN URI（OPC UA Application URI，必填）
     * @param sanDns SAN DNS（可选，传 null 则跳过）
     * @param kp     RSA 密钥对
     */
    private static X509Certificate buildSelfSignedCert(
            String dn, String sanUri, String sanDns, KeyPair kp) throws Exception {

        var subject = new X500Name(dn);
        var notBefore = new Date();
        var notAfter = new Date(notBefore.getTime() + 10L * 365 * 24 * 3600 * 1000);
        var serial = BigInteger.valueOf(System.nanoTime());

        var certBuilder = new JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, kp.getPublic());

        // SAN extension（OPC UA 要求应用证书必须包含 URI SAN）
        var sanEntries = sanDns != null
            ? new GeneralName[]{
                new GeneralName(GeneralName.uniformResourceIdentifier, sanUri),
                new GeneralName(GeneralName.dNSName, sanDns)}
            : new GeneralName[]{
                new GeneralName(GeneralName.uniformResourceIdentifier, sanUri)};

        certBuilder.addExtension(
            Extension.subjectAlternativeName,
            false,
            new GeneralNames(sanEntries));

        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(certBuilder.build(signer));
    }

    /** X509Certificate → PEM CERTIFICATE block. */
    private static String toPem(X509Certificate cert) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
            + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(cert.getEncoded())
            + "\n-----END CERTIFICATE-----\n";
    }

    /** KeyPair 私钥 → PEM PRIVATE KEY（PKCS#8 unencrypted）block. */
    private static String toPkcs8Pem(KeyPair kp) {
        return "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(kp.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    }
}
