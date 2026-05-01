package com.ems.collector.secret;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;

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
    @DisplayName("init() heal-on-init：dir 已存在且 perms 松（含 GROUP/OTHER 位）会被收紧到 0700")
    void init_existingDirWithLoosePerms_isHealedTo700(@TempDir Path loose) throws Exception {
        Files.setPosixFilePermissions(loose, Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE));

        var resolver = new FilesystemSecretResolver(loose);
        resolver.init();

        // Heal 后 perms 必须仅为 OWNER_*；不再含 GROUP/OTHER 位。
        assertThat(Files.getPosixFilePermissions(loose)).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    }

    @Test
    @DisplayName("init() 校验残留兜底：chmod 静默 no-op 时仍能检出松权限并抛 SecurityException")
    void init_chmodSilentNoop_stillDetectsLoosePermsAndThrows(@TempDir Path loose) throws Exception {
        // 实环境提前布置松权限，再进入 mockStatic 块。这样 mock 不会影响 setUp 的 Files 调用，
        // 而 init() 内部对 Files.setPosixFilePermissions 的调用会被替换成 no-op，
        // 模拟「FS 驱动接收 chmod 但未生效」这一回归类失效模式。
        Files.setPosixFilePermissions(loose, Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE));

        var resolver = new FilesystemSecretResolver(loose);

        try (MockedStatic<Files> mocked = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS)) {
            mocked.when(() -> Files.setPosixFilePermissions(any(), any()))
                  .thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(resolver::init)
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("unsafe permission");
        }
    }

    @Test
    @DisplayName("init() 原子创建：dir 不存在时调用 2-arg createDirectories(dir, attr)，避免 0755 短窗口")
    void init_dirDoesNotExist_usesAtomicCreateWithPosixAttr(@TempDir Path parent) throws Exception {
        // 选一个尚不存在的子目录作为 secretsDir，强制走「创建」分支
        Path newDir = parent.resolve("secrets-fresh");
        assertThat(Files.exists(newDir)).isFalse();

        var resolver = new FilesystemSecretResolver(newDir);

        try (MockedStatic<Files> mocked = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS)) {
            resolver.init();

            // 关键回归保护：dir 创建必须用带 PosixFilePermissions attr 的 2-arg 版本，
            // 而不是先 1-arg 创建 0755 再 chmod 0700。后者存在短暂的 GROUP/OTHER 暴露窗口。
            mocked.verify(
                () -> Files.createDirectories(eq(newDir), any(FileAttribute.class)),
                atLeastOnce());
        }

        // 行为兜底：最终权限必须是 0700
        assertThat(Files.getPosixFilePermissions(newDir)).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    }
}
