package com.ems.collector.cert;

import com.ems.collector.transport.TransportException;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * PEM 格式客户端证书 + PKCS#8 私钥加载器（用于 OPC UA SecurityMode SIGN）。
 *
 * <p>支持：
 * <ul>
 *   <li>PKCS#8 unencrypted ({@code -----BEGIN PRIVATE KEY-----})
 *   <li>PKCS#8 encrypted  ({@code -----BEGIN ENCRYPTED PRIVATE KEY-----})
 * </ul>
 *
 * <p>不支持 PKCS#1 ({@code -----BEGIN RSA PRIVATE KEY-----}) — 留 follow-up。
 */
public final class OpcUaCertificateLoader {

    private static final Pattern PEM_BLOCK = Pattern.compile(
        "-----BEGIN ([^-]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----",
        Pattern.DOTALL);

    private OpcUaCertificateLoader() {}

    /**
     * 解析 PEM 文本（包含 CERTIFICATE + PRIVATE KEY 两段），返回 {@link ClientKeyMaterial}。
     *
     * @param pem         PEM 文本（cert + key 按任意顺序拼接均可）
     * @param keyPassword 私钥密码；未加密时传 {@code null}
     * @return ClientKeyMaterial
     * @throws TransportException 解析失败、缺段、密码错误等
     */
    public static ClientKeyMaterial loadClientKeyMaterial(String pem, String keyPassword) {
        if (pem == null || pem.isBlank()) {
            throw new TransportException("pem is empty");
        }

        // 规范化行结束符，防止 Windows CRLF 导致 label group 捕获多余 \r
        pem = pem.replace("\r\n", "\n").replace("\r", "\n");

        String certBlock    = extractBlock(pem, "CERTIFICATE");
        String keyBlock     = extractBlock(pem, "PRIVATE KEY");
        String encKeyBlock  = extractBlock(pem, "ENCRYPTED PRIVATE KEY");

        if (certBlock == null) {
            throw new TransportException("missing CERTIFICATE block in pem");
        }
        if (keyBlock == null && encKeyBlock == null) {
            throw new TransportException("missing PRIVATE KEY block in pem");
        }

        X509Certificate cert = parseCert(certBlock);
        PrivateKey privateKey;
        if (encKeyBlock != null) {
            privateKey = parseEncryptedKey(encKeyBlock, keyPassword, cert.getPublicKey().getAlgorithm());
        } else {
            privateKey = parseUnencryptedKey(keyBlock, cert.getPublicKey().getAlgorithm());
        }

        return new ClientKeyMaterial(cert, new KeyPair(cert.getPublicKey(), privateKey));
    }

    // ---- helpers ------------------------------------------------------------

    private static X509Certificate parseCert(String certBlock) {
        try {
            var cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(certBlock.getBytes(UTF_8)));
        } catch (Exception e) {
            throw new TransportException("invalid client certificate: " + e.getMessage(), e);
        }
    }

    private static PrivateKey parseUnencryptedKey(String keyBlock, String algorithm) {
        try {
            var keyBytes = decodeBase64Body(keyBlock);
            return KeyFactory.getInstance(algorithm)
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (TransportException e) {
            throw e;
        } catch (Exception e) {
            throw new TransportException("invalid private key: " + e.getMessage(), e);
        }
    }

    private static PrivateKey parseEncryptedKey(String encKeyBlock, String keyPassword, String algorithm) {
        if (keyPassword == null) {
            throw new TransportException("encrypted private key requires a password (certPasswordRef)");
        }
        char[] pwd = keyPassword.toCharArray();
        PBEKeySpec spec = null;
        try {
            var keyBytes = decodeBase64Body(encKeyBlock);
            var epki    = new EncryptedPrivateKeyInfo(keyBytes);
            spec        = new PBEKeySpec(pwd);
            var skf     = SecretKeyFactory.getInstance(epki.getAlgName());
            var pbeKey  = skf.generateSecret(spec);
            var cipher  = Cipher.getInstance(epki.getAlgName());
            cipher.init(Cipher.DECRYPT_MODE, pbeKey, epki.getAlgParameters());
            var pkcs8Spec = epki.getKeySpec(cipher);
            return KeyFactory.getInstance(algorithm).generatePrivate(pkcs8Spec);
        } catch (TransportException e) {
            throw e;
        } catch (Exception e) {
            throw new TransportException("invalid encrypted private key: " + e.getMessage(), e);
        } finally {
            if (spec != null) spec.clearPassword();
            java.util.Arrays.fill(pwd, '\0');
        }
    }

    /**
     * 从 PEM 文本中提取指定标签的第一个块（含 BEGIN/END 行）。
     *
     * <p>注意：先尝试精确匹配 label，因此 "PRIVATE KEY" 不会意外匹配
     * "ENCRYPTED PRIVATE KEY"（正则捕获组要求 BEGIN/END 标签完全一致）。
     */
    private static String extractBlock(String pem, String label) {
        var matcher = PEM_BLOCK.matcher(pem);
        while (matcher.find()) {
            if (matcher.group(1).equals(label)) {
                return matcher.group(0);
            }
        }
        return null;
    }

    /** 从 PEM 块文本中剥离 BEGIN/END 行和空白，base64 解码为字节。 */
    private static byte[] decodeBase64Body(String pemBlock) {
        var body = pemBlock
            .replaceAll("-----BEGIN [^-]+-----", "")
            .replaceAll("-----END [^-]+-----", "")
            .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(body);
    }

    // ---- value type ---------------------------------------------------------

    /** 客户端 TLS 身份材料：证书 + 密钥对。 */
    public record ClientKeyMaterial(X509Certificate certificate, KeyPair keyPair) {}
}
