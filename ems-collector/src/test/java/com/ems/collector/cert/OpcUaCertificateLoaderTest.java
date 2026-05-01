package com.ems.collector.cert;

import com.ems.collector.transport.TransportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpcUaCertificateLoader 单元测试。
 *
 * <p>PEM fixture 由 openssl 离线生成（RSA 2048, self-signed /CN=ems-test）。
 * 加密版使用密码 "test123"（AES-256-CBC PBKDF2 via openssl pkcs8 -topk8）。
 */
@DisplayName("OpcUaCertificateLoader")
class OpcUaCertificateLoaderTest {

    // ---- PEM Fixtures -------------------------------------------------------

    private static final String CERT = """
        -----BEGIN CERTIFICATE-----
        MIIDBzCCAe+gAwIBAgIUZKlNhh39YdsW9dBoT4NXGuq6V6cwDQYJKoZIhvcNAQEL
        BQAwEzERMA8GA1UEAwwIZW1zLXRlc3QwHhcNMjYwNTAxMDgyMDI3WhcNMzYwNDI4
        MDgyMDI3WjATMREwDwYDVQQDDAhlbXMtdGVzdDCCASIwDQYJKoZIhvcNAQEBBQAD
        ggEPADCCAQoCggEBAJ4KBQrVU9Zal1NKK91E2TvYj+5LeaG2EMuPMtAQd0WAeBtC
        t5OzyIpbNM/0wgXvFMFkZxPTgUjcHv0oWLpwECf7wegxyLlxIumC1XQEMECqYuTN
        rfSA1Gqqrbzi65jB9mJ9toArwuN0BJAilG456cDpIL5PqIRT8p9b/JeopfW2VM+D
        tNoPL1Wo1rL0CWwL2j6jatA+ZSEGmg0DhEpjVqZVcN8MIVMkuazkrr/VH/rmRKcR
        eXI+E5tdHYTu/KhrM7R761YpSRh3MONl+r8MHRXFwQ3Fl184KNW0ql+sQFwBnc4L
        EW6flylTG4BkFnbKXXyuysMu4s7q2Za7j7jWc8ECAwEAAaNTMFEwHQYDVR0OBBYE
        FNKzUX8pnaIiHR9V7PpdUaq78vxsMB8GA1UdIwQYMBaAFNKzUX8pnaIiHR9V7Ppd
        Uaq78vxsMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAEbcviog
        CqrzvR7b/ZLjQhRUDx19IiO1coiytaywTjPemuc/gOdEpRvd6ZV5Ul7asPAYwRBd
        43ZHKTr+m0+5QHSnXOcn6uMs1gHTy1BEdyQilYEotuMx98SnH2zAfupPPZlVaqzK
        BpMcIGW2C5DNkfJS/hGs32THGpWhoYl+cocl8AymijxdkTPUqu4c8Bd6ZivOfTu4
        /90ZAYVKGokWp26O2TkqfXOCxJTCgGB0DwbywzARrUIFVl3wSC2H2TNS3Pihhj1p
        kpfSgmMO99B3CGUWk9HF2C1ctRXAuT+V3fnxXL+Ap57ySEs3XuCM4mykRIXmNDvA
        pYgCG1n8PxZgUck=
        -----END CERTIFICATE-----
        """;

    private static final String PRIVATE_KEY_PKCS8 = """
        -----BEGIN PRIVATE KEY-----
        MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCeCgUK1VPWWpdT
        SivdRNk72I/uS3mhthDLjzLQEHdFgHgbQreTs8iKWzTP9MIF7xTBZGcT04FI3B79
        KFi6cBAn+8HoMci5cSLpgtV0BDBAqmLkza30gNRqqq284uuYwfZifbaAK8LjdASQ
        IpRuOenA6SC+T6iEU/KfW/yXqKX1tlTPg7TaDy9VqNay9AlsC9o+o2rQPmUhBpoN
        A4RKY1amVXDfDCFTJLms5K6/1R/65kSnEXlyPhObXR2E7vyoazO0e+tWKUkYdzDj
        Zfq/DB0VxcENxZdfOCjVtKpfrEBcAZ3OCxFun5cpUxuAZBZ2yl18rsrDLuLO6tmW
        u4+41nPBAgMBAAECggEAFq1hY9iQmyWMqUrvWGTUtx2wKYVzAp1Zc5wssmZqdvKk
        SWUdanhVwmSsA1sfrDJOTCHYGKQ03PuZBsKPWNFTwCkD/gmGv/VYCSHY9zjSo7TP
        U/2fhIIn+NZc78mv/M94R1G6qlGdOX4gWX5cxiRRQnkxVZ2glLxnCC7u4+QzTDqP
        3zcJ0bCI+wHakFY0OIp1kyAUgtcXdWih0fLjtUL7QPrjYJFhblppN6OKKyU0H/5j
        GYpQjNeT0EBcrPyyjQ9PNq1gPbWL7WeviOAD+mqMLVMrt7jeuPcNtCnk/NE4Th1C
        lH047L5SP1v3RSfK7HCzPTOXCtkFqZS0EeV5xL8LQQKBgQDJrJgkBoTP8EDmwxwS
        QrO2S7pX7YD/9UmcPsH9S3GTAWbh3K5tLHCHrzcFdgGhNAyQXDhLgfUWlHVzIy7o
        wg7cvRxuYws3yNmh+YNGNjrckq2eDMaMT7+lTBtLggWq15Sx8f8eF7zwLpN0+dVe
        4yl62rPs/lRFqeld9MfYjAhsBQKBgQDInFysksu+JIoV/Rt2RSWqthrYqLZdGXE5
        n/VeRFfHrJrQCAH2zIwisMgSgwKqFRLlZ1bICmpKc8o9dI0FXqIhcoLoH0cu9SN3
        mI6eePWo1P9IDnuL76BnWzkUo1Wl+k+AjjIh5AbwpvO8tWfXXbz80stiOLYDUj1T
        0SsKak4xjQKBgQC1D0VQIqIeZZ5/DvAm3MenhHl583r7UWaS5i0XkSIYTwvk/1GC
        JoGo73/AYV+5MJePC5xm/ffiG8sOYan9wivBte7OlBFANgxdxTs8T/esi+tfmCyx
        T+/JIXjn44XfCckvsQnQJJO2NrqgLKSJmfxllfAvd26FVLR1bAv3uFDfWQKBgCl6
        41Kvm786MO8xAa+nxo0Q0GZCJEsFyrqej46pVPktgGlJbpaIXuWBZi0lt7RRXTHf
        9zqvpKC2ZnfklJAqrB18XwL+DKrx9x5whfTQkAMT36EXlYYuyxzz6M6So1AJfMzZ
        GhaV3rV93+ICJHGMqVohf/pUPZZcN+AP3PVvW7G1AoGAVAAM63MV2gyfFpvrKiE4
        BEt29FQvKWYSKTNwlJ6ptTP1sJVmo3tzSvew5sFCXcfkNLlFal7lLzxw5s4FVQfF
        Ub/NIvBmkhTbKgfrSCjvT8vprNFpYZWaC+ozVMrZVGBJ+ZEV4EB9rr78bMQM6rNE
        KQM/+bV2ajHmwmk5tdImgtY=
        -----END PRIVATE KEY-----
        """;

    private static final String PRIVATE_KEY_ENCRYPTED = """
        -----BEGIN ENCRYPTED PRIVATE KEY-----
        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
        -----END ENCRYPTED PRIVATE KEY-----
        """;

    private static final String VALID_PEM = CERT + PRIVATE_KEY_PKCS8;
    private static final String VALID_PEM_ENCRYPTED = CERT + PRIVATE_KEY_ENCRYPTED;

    // ---- Tests --------------------------------------------------------------

    @Test
    @DisplayName("有效 unencrypted PEM → 返回 cert 和 KeyPair 非 null")
    void loadClientKeyMaterial_withValidUnencryptedPem_returnsKeyMaterial() {
        var result = OpcUaCertificateLoader.loadClientKeyMaterial(VALID_PEM, null);

        assertThat(result).isNotNull();
        assertThat(result.certificate()).isNotNull();
        assertThat(result.certificate().getSubjectX500Principal().getName())
            .contains("ems-test");
        assertThat(result.keyPair()).isNotNull();
        assertThat(result.keyPair().getPublic()).isNotNull();
        assertThat(result.keyPair().getPrivate()).isNotNull();
    }

    @Test
    @DisplayName("无效 PEM 内容 → 抛 TransportException")
    void loadClientKeyMaterial_withInvalidPem_throws() {
        assertThatThrownBy(() -> OpcUaCertificateLoader.loadClientKeyMaterial("garbage", null))
            .isInstanceOf(TransportException.class);
    }

    @Test
    @DisplayName("null/blank PEM → 抛 TransportException")
    void loadClientKeyMaterial_withBlankPem_throws() {
        assertThatThrownBy(() -> OpcUaCertificateLoader.loadClientKeyMaterial(null, null))
            .isInstanceOf(TransportException.class);

        assertThatThrownBy(() -> OpcUaCertificateLoader.loadClientKeyMaterial("   ", null))
            .isInstanceOf(TransportException.class);
    }

    @Test
    @DisplayName("缺少 CERTIFICATE 段 → 抛 TransportException 含 'CERTIFICATE'")
    void loadClientKeyMaterial_missingCertSection_throws() {
        assertThatThrownBy(
            () -> OpcUaCertificateLoader.loadClientKeyMaterial(PRIVATE_KEY_PKCS8, null))
            .isInstanceOf(TransportException.class)
            .hasMessageContaining("CERTIFICATE");
    }

    @Test
    @DisplayName("缺少 PRIVATE KEY 段 → 抛 TransportException 含 'PRIVATE KEY'")
    void loadClientKeyMaterial_missingKeySection_throws() {
        assertThatThrownBy(
            () -> OpcUaCertificateLoader.loadClientKeyMaterial(CERT, null))
            .isInstanceOf(TransportException.class)
            .hasMessageContaining("PRIVATE KEY");
    }

    @Test
    @DisplayName("加密 key + 正确密码 → 返回 KeyMaterial")
    void loadClientKeyMaterial_withEncryptedKey_andCorrectPassword_returnsKeyMaterial() {
        var result = OpcUaCertificateLoader.loadClientKeyMaterial(VALID_PEM_ENCRYPTED, "test123");

        assertThat(result).isNotNull();
        assertThat(result.certificate()).isNotNull();
        assertThat(result.keyPair().getPrivate()).isNotNull();
    }

    @Test
    @DisplayName("加密 key + 错误密码 → 抛 TransportException")
    void loadClientKeyMaterial_withEncryptedKey_andWrongPassword_throws() {
        assertThatThrownBy(
            () -> OpcUaCertificateLoader.loadClientKeyMaterial(VALID_PEM_ENCRYPTED, "wrong"))
            .isInstanceOf(TransportException.class);
    }
}
