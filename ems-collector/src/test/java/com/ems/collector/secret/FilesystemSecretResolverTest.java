package com.ems.collector.secret;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FilesystemSecretResolver")
class FilesystemSecretResolverTest {
    @TempDir Path dir;
    SecretResolver r;

    @BeforeEach
    void setUp() throws Exception {
        Files.setPosixFilePermissions(dir, Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
        r = new FilesystemSecretResolver(dir);
        ((FilesystemSecretResolver) r).init();
    }

    @Test
    @DisplayName("write 后 resolve 返回原值")
    void writeAndResolve_existingSecret_returnsValue() {
        r.write("secret://mqtt/p", "s3cret");
        assertThat(r.resolve("secret://mqtt/p")).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("write 文件权限为 600")
    void write_fileSetTo600Permissions() throws Exception {
        r.write("secret://opcua/u", "user");
        var perms = Files.getPosixFilePermissions(dir.resolve("opcua/u"));
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    @DisplayName("路径遍历 secret://../etc/passwd 抛 SecurityException")
    void resolve_pathTraversal_throwsSecurityException() {
        assertThatThrownBy(() -> r.resolve("secret://../../../etc/passwd"))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("非 secret:// 前缀抛 IllegalArgumentException")
    void resolve_invalidScheme_throwsIllegalArgument() {
        assertThatThrownBy(() -> r.resolve("file:///etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("不存在的 ref 调 exists 返回 false")
    void exists_missingRef_returnsFalse() {
        assertThat(r.exists("secret://x/y")).isFalse();
    }

    @Test
    @DisplayName("delete 后 exists 返回 false")
    void delete_afterWrite_existsReturnsFalse() {
        r.write("secret://a/b", "x");
        r.delete("secret://a/b");
        assertThat(r.exists("secret://a/b")).isFalse();
    }

    @Test
    @DisplayName("listRefs 返回所有 secret:// 引用")
    void listRefs_afterMultipleWrites_returnsAllRefs() {
        r.write("secret://mqtt/u", "v1");
        r.write("secret://opcua/p", "v2");
        assertThat(r.listRefs()).containsExactlyInAnyOrder(
            "secret://mqtt/u", "secret://opcua/p");
    }

    @Test
    @DisplayName("init() 检测到不安全权限（含 GROUP/OTHER 位）抛 SecurityException")
    void init_unsafePermissions_throwsSecurityException(@TempDir Path unsafe) throws Exception {
        Files.setPosixFilePermissions(unsafe, Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE));
        var resolver = new FilesystemSecretResolver(unsafe);
        assertThatThrownBy(resolver::init)
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("unsafe permission");
    }
}
