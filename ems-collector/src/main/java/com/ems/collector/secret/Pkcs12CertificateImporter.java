package com.ems.collector.secret;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import java.security.AlgorithmParameters;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * 把 PKCS#12 (.pfx) 二进制解析成 OpcUaCertificateLoader 可消费的 PEM 文本。
 *
 * <p>不落盘：纯内存转换。调用方拿到 {@link ImportResult} 后通过
 * {@code SecretResolver.write} 把 {@code certificatePem + "\n" + encryptedKeyPem}
 * 写到 {@code secret://opcua/<name>.pem}，把 {@code password} 写到
 * {@code secret://opcua/<name>.pem.password}。
 *
 * <p>encrypted PKCS#8 算法用 {@code PBEWithHmacSHA256AndAES_128}（JDK 17+ 内置）。
 */
public final class Pkcs12CertificateImporter {

    /**
     * 重新加密生成的 EncryptedPrivateKeyInfo 用此算法。
     *
     * <p>使用 PKCS#12 PBE-SHA1-3DES（OID 1.2.840.113549.1.12.1.3）——这是 stock JDK
     * 的 {@link EncryptedPrivateKeyInfo} 唯一能 OID round-trip 的常见 PBE 算法，
     * 现代 PBES2（PBEWithHmacSHA256AndAES_128 等）只在 SecretKeyFactory 注册名字
     * 但 OID 注册表查不到，构造 EPKI 会抛 NoSuchAlgorithmException。
     *
     * <p>SHA1+3DES 虽不是 2026 推荐算法，但 .pfx 文件本身也是同档强度，且密码
     * 经 ADMIN 控制；OPC UA Transport 的 OpcUaCertificateLoader 会解密一次后
     * 装入内存——磁盘加密强度等同于上传的原 .pfx。
     */
    private static final String PBE_ALG = "PBEWithSHA1AndDESede";
    /** PBE 迭代次数；行业典型 50_000+。 */
    private static final int PBE_ITERATIONS = 50_000;
    /** PBE salt 长度（字节）。SHA1-3DES 接受 16 字节 salt。 */
    private static final int PBE_SALT_LEN = 16;

    private Pkcs12CertificateImporter() {}

    public record ImportResult(String certificatePem, String encryptedKeyPem, String fingerprintHex) {}

    /**
     * 解析 PFX 字节，返回 PEM 字符串 + cert SHA-256 指纹（hex 小写）。
     *
     * @param pfxBytes      PKCS#12 keystore 字节（必填，非空）
     * @param password      keystore 密码 + key 密码（按 PFX 通常约定一致）
     * @param aliasOrNull   keystore 内的 entry alias；keystore 内仅有一个 private-key
     *                      entry 时可传 null
     * @return ImportResult
     * @throws IllegalArgumentException 任何解析 / 选 alias / 密码错误
     */
    public static ImportResult importPfx(byte[] pfxBytes, String password, String aliasOrNull) {
        if (pfxBytes == null || pfxBytes.length == 0) {
            throw new IllegalArgumentException("pfx bytes empty");
        }
        if (password == null) {
            throw new IllegalArgumentException("keystore password is required");
        }

        KeyStore ks;
        try {
            ks = KeyStore.getInstance("PKCS12");
        } catch (Exception e) {
            throw new IllegalArgumentException("PKCS12 not supported: " + e.getMessage(), e);
        }

        char[] pwd = password.toCharArray();
        try {
            try (var in = new java.io.ByteArrayInputStream(pfxBytes)) {
                ks.load(in, pwd);
            } catch (java.io.IOException e) {
                // KeyStore.load 把 wrong password 包成 IOException(cause=UnrecoverableKeyException)
                Throwable cause = e.getCause();
                if (cause instanceof UnrecoverableKeyException
                    || (cause != null && cause.getMessage() != null
                        && cause.getMessage().toLowerCase().contains("password"))) {
                    throw new IllegalArgumentException("invalid keystore password", e);
                }
                throw new IllegalArgumentException("invalid pfx bytes: " + e.getMessage(), e);
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid pfx bytes: " + e.getMessage(), e);
            }

            String alias = pickAlias(ks, aliasOrNull);

            Key key;
            try {
                key = ks.getKey(alias, pwd);
            } catch (UnrecoverableKeyException e) {
                throw new IllegalArgumentException("invalid keystore password", e);
            } catch (Exception e) {
                throw new IllegalArgumentException("cannot read key: " + e.getMessage(), e);
            }
            if (!(key instanceof PrivateKey privateKey)) {
                throw new IllegalArgumentException("alias " + alias + " is not a private-key entry");
            }

            Certificate cert;
            try {
                cert = ks.getCertificate(alias);
            } catch (Exception e) {
                throw new IllegalArgumentException("cannot read certificate: " + e.getMessage(), e);
            }
            if (!(cert instanceof X509Certificate x509)) {
                throw new IllegalArgumentException("alias " + alias + " has no X.509 certificate");
            }

            String certPem = encodePem("CERTIFICATE", x509.getEncoded());
            String keyPem = encryptAndEncodePem(privateKey, pwd);
            String fingerprint = sha256Hex(x509.getEncoded());
            return new ImportResult(certPem, keyPem, fingerprint);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("pfx import failed: " + e.getMessage(), e);
        } finally {
            // 清掉 char[] 副本（password 字符串本身仍由调用方持有）
            java.util.Arrays.fill(pwd, '\0');
        }
    }

    // ---- helpers ------------------------------------------------------------

    private static String pickAlias(KeyStore ks, String requested) throws Exception {
        if (requested != null && !requested.isBlank()) {
            if (!ks.containsAlias(requested)) {
                throw new IllegalArgumentException("alias not found in keystore: " + requested);
            }
            if (!ks.isKeyEntry(requested)) {
                throw new IllegalArgumentException("alias is not a key entry: " + requested);
            }
            return requested;
        }
        List<String> keyAliases = new ArrayList<>();
        for (String a : Collections.list(ks.aliases())) {
            if (ks.isKeyEntry(a)) keyAliases.add(a);
        }
        if (keyAliases.isEmpty()) {
            throw new IllegalArgumentException("keystore has no private-key entries");
        }
        if (keyAliases.size() > 1) {
            throw new IllegalArgumentException(
                "keystore has multiple key aliases " + keyAliases
                + "; specify which alias to import");
        }
        return keyAliases.get(0);
    }

    private static String encryptAndEncodePem(PrivateKey privateKey, char[] password) throws Exception {
        byte[] salt = new byte[PBE_SALT_LEN];
        new SecureRandom().nextBytes(salt);

        var pbeSpec = new PBEParameterSpec(salt, PBE_ITERATIONS);
        var keySpec = new PBEKeySpec(password);
        try {
            var skf = SecretKeyFactory.getInstance(PBE_ALG);
            var pbeKey = skf.generateSecret(keySpec);
            var cipher = Cipher.getInstance(PBE_ALG);
            cipher.init(Cipher.ENCRYPT_MODE, pbeKey, pbeSpec);
            byte[] encrypted = cipher.doFinal(privateKey.getEncoded());

            AlgorithmParameters params = AlgorithmParameters.getInstance(PBE_ALG);
            params.init(pbeSpec);
            var epki = new EncryptedPrivateKeyInfo(params, encrypted);
            return encodePem("ENCRYPTED PRIVATE KEY", epki.getEncoded());
        } finally {
            keySpec.clearPassword();
        }
    }

    private static String encodePem(String label, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + b64 + "\n-----END " + label + "-----\n";
    }

    private static String sha256Hex(byte[] der) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(der));
    }
}
