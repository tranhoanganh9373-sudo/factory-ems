package com.ems.collector.cert;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.stream.Stream;

@Component
public class OpcUaCertificateStore {

    private final Path trustedDir;

    public OpcUaCertificateStore(
            @Value("${ems.secrets.dir:#{systemProperties['user.home']}/.ems/secrets}") Path secretsDir) {
        this.trustedDir = secretsDir.resolve("opcua/certs/trusted");
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(trustedDir);
    }

    public boolean isTrusted(X509Certificate cert) throws IOException, NoSuchAlgorithmException, CertificateEncodingException {
        var thumb = thumbprint(cert);
        try (Stream<Path> stream = Files.list(trustedDir)) {
            return stream.anyMatch(p -> p.getFileName().toString().endsWith(thumb + ".der"));
        }
    }

    public void approve(X509Certificate cert, String displayName) throws IOException, NoSuchAlgorithmException, CertificateEncodingException {
        var thumb = thumbprint(cert);
        var name = (displayName != null ? displayName : "cert") + "-" + thumb + ".der";
        Files.write(trustedDir.resolve(name), cert.getEncoded());
    }

    public String thumbprint(X509Certificate cert) throws NoSuchAlgorithmException, CertificateEncodingException {
        var md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(cert.getEncoded()));
    }
}
