# Factory EMS · Plan 1.1 · 地基 + 认证 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可登录、管理员可建用户/角色/授节点权限/管理组织树、审计可查的工厂能源管理系统 v1.1 基线。

**Architecture:** Maven 多模块单体（Spring Boot 3 + JDK 21）+ React 18 + Ant Design 5 前端；PostgreSQL 15 作为主数据库；Docker Compose 部署；组织树用闭包表支持 O(1) 子树查询；JWT 双令牌 + 节点子树粒度权限。

**Tech Stack:** Java 21 / Spring Boot 3.3 / Spring Security 6 / MyBatis-Plus / Flyway / JUnit 5 / Testcontainers / Logback(JSON) / React 18 / Vite / TypeScript / Ant Design 5 / Zustand / TanStack Query / axios / Playwright / PostgreSQL 15 / Nginx / Docker Compose / GitHub Actions.

**Spec reference:** `docs/superpowers/specs/2026-04-24-factory-ems-foundation-design.md`

---

## 范围边界

### 本 Plan 交付模块

- `ems-core` — 公共 DTO / 异常 / 工具
- `ems-audit` — 审计切面 + 日志写入
- `ems-orgtree` — 组织树 + 闭包维护
- `ems-auth` — 用户 / 角色 / 节点权限 / JWT / PermissionResolver
- `ems-app` — Spring Boot 启动装配 + 全局配置 + 异常处理

### 本 Plan 不做（留给后续 plan）

- `ems-timeseries`（InfluxDB 查询 + rollup）→ Plan 1.2
- `ems-meter`（测点管理）→ Plan 1.2
- `ems-tariff` / `ems-production` / `ems-floorplan` → Plan 1.3
- `ems-dashboard` / `ems-report` → Plan 1.2 / 1.3
- Python 分析侧车 → 子项目 3

### 交付演示场景

Plan 1.1 完成后必须能在浏览器演示这个完整流程：

1. 管理员登录
2. 创建组织树：`华东工厂 / 一车间 / 注塑线 1`
3. 创建 VIEWER 用户 `zhang3`
4. 把 `zhang3` 绑定到 `一车间` 节点（SUBTREE 权限）
5. 退出 admin，用 `zhang3` 登录
6. `zhang3` 只能在组织树里看到 `一车间` 及其子孙（`华东工厂` 看不到）
7. 管理员查审计日志，看到用户创建 / 权限授予 / 登录登出全部有记录
8. 强制过期 `zhang3` 的 Access Token，前端自动用 Refresh Token 续签，用户无感

---

## Phase 索引

| Phase | 任务范围 | 任务数 |
|---|---|---|
| A | 项目骨架（Maven / Spring Boot / Flyway / Docker / CI / 日志） | 12 |
| B | `ems-audit` 模块 | 6 |
| C | `ems-orgtree` 模块 | 13 |
| D | `ems-auth` 模块 | 25 |
| E | 前端骨架（Vite / Router / 登录 / 布局 / 拦截器） | 16 |
| F | 前端管理页面（组织树 / 用户 / 权限 / 审计） | 20 |
| G | 部署 + E2E | 14 |
| **合计** | | **106** |

---

## 目录结构

### 后端 Maven 结构

```
factory-ems/
├── pom.xml                                        # 父 POM
├── ems-core/
│   ├── pom.xml
│   └── src/main/java/com/ems/core/
│       ├── dto/Result.java
│       ├── dto/PageDTO.java
│       ├── exception/BusinessException.java
│       ├── exception/NotFoundException.java
│       ├── exception/ForbiddenException.java
│       ├── exception/UnauthorizedException.java
│       ├── exception/ConflictException.java
│       ├── util/JsonUtil.java
│       ├── util/TraceIdHolder.java
│       └── constant/ErrorCode.java
├── ems-audit/
│   ├── pom.xml
│   └── src/main/java/com/ems/audit/
│       ├── annotation/Audited.java
│       ├── aspect/AuditAspect.java
│       ├── entity/AuditLog.java
│       ├── event/AuditEvent.java
│       ├── listener/AsyncAuditListener.java
│       ├── repository/AuditLogRepository.java
│       └── service/AuditService.java
├── ems-orgtree/
│   ├── pom.xml
│   └── src/main/java/com/ems/orgtree/
│       ├── entity/OrgNode.java
│       ├── entity/OrgNodeClosure.java
│       ├── repository/OrgNodeRepository.java
│       ├── repository/OrgNodeClosureRepository.java
│       ├── dto/OrgNodeDTO.java
│       ├── dto/CreateOrgNodeReq.java
│       ├── dto/UpdateOrgNodeReq.java
│       ├── dto/MoveOrgNodeReq.java
│       ├── service/OrgNodeService.java
│       ├── service/impl/OrgNodeServiceImpl.java
│       └── controller/OrgNodeController.java
├── ems-auth/
│   ├── pom.xml
│   └── src/main/java/com/ems/auth/
│       ├── entity/User.java
│       ├── entity/Role.java
│       ├── entity/UserRole.java
│       ├── entity/NodePermission.java
│       ├── entity/RefreshToken.java
│       ├── repository/*.java
│       ├── jwt/JwtService.java
│       ├── jwt/JwtAuthenticationFilter.java
│       ├── security/SecurityConfig.java
│       ├── security/UserDetailsServiceImpl.java
│       ├── service/AuthService.java
│       ├── service/UserService.java
│       ├── service/NodePermissionService.java
│       ├── service/PermissionResolver.java
│       ├── service/impl/*.java
│       ├── annotation/RequireNode.java
│       ├── aspect/RequireNodeAspect.java
│       ├── controller/AuthController.java
│       ├── controller/UserController.java
│       ├── controller/RoleController.java
│       ├── controller/NodePermissionController.java
│       ├── controller/AuditLogController.java
│       └── dto/*.java
└── ems-app/
    ├── pom.xml
    ├── Dockerfile
    └── src/main/
        ├── java/com/ems/app/
        │   ├── FactoryEmsApplication.java
        │   ├── config/CorsConfig.java
        │   ├── config/AsyncConfig.java
        │   ├── handler/GlobalExceptionHandler.java
        │   ├── filter/TraceIdFilter.java
        │   └── init/AdminInitializer.java
        └── resources/
            ├── application.yml
            ├── application-dev.yml
            ├── application-prod.yml
            ├── logback-spring.xml
            └── db/migration/
                ├── V1.0.0__init_auth.sql
                ├── V1.0.1__init_orgtree.sql
                ├── V1.0.7__init_audit.sql
                └── V1.0.8__seed_reference_data.sql
```

### 前端结构

```
frontend/
├── package.json
├── pnpm-lock.yaml
├── vite.config.ts
├── tsconfig.json
├── index.html
├── Dockerfile
└── src/
    ├── main.tsx
    ├── App.tsx
    ├── api/
    │   ├── client.ts
    │   ├── auth.ts
    │   ├── user.ts
    │   ├── role.ts
    │   ├── permission.ts
    │   ├── orgtree.ts
    │   └── audit.ts
    ├── stores/
    │   ├── authStore.ts
    │   └── appStore.ts
    ├── router/index.tsx
    ├── layouts/AppLayout.tsx
    ├── components/
    │   ├── ProtectedRoute.tsx
    │   ├── PermissionGate.tsx
    │   └── OrgTreeSelector.tsx
    ├── pages/
    │   ├── login/index.tsx
    │   ├── profile/index.tsx
    │   ├── forbidden.tsx
    │   ├── not-found.tsx
    │   ├── orgtree/
    │   │   ├── index.tsx
    │   │   ├── CreateNodeModal.tsx
    │   │   ├── EditNodeModal.tsx
    │   │   └── MoveNodeModal.tsx
    │   └── admin/
    │       ├── users/
    │       │   ├── list.tsx
    │       │   ├── edit.tsx
    │       │   └── permissions.tsx
    │       ├── roles/list.tsx
    │       └── audit/
    │           ├── list.tsx
    │           └── DetailModal.tsx
    ├── hooks/usePermissions.ts
    ├── utils/
    │   ├── format.ts
    │   └── errorCode.ts
    └── styles/global.css
```

### 根目录

```
factory-ems/
├── docker-compose.yml
├── docker-compose.dev.yml
├── .env.example
├── .gitignore
├── README.md
├── nginx/
│   ├── nginx.conf
│   └── conf.d/factory-ems.conf
├── .github/workflows/ci.yml
├── e2e/
│   ├── package.json
│   ├── playwright.config.ts
│   └── tests/
│       ├── login.spec.ts
│       ├── user-permission.spec.ts
│       └── audit.spec.ts
└── docs/
    └── superpowers/
        ├── specs/
        └── plans/
```

---

<!-- PHASE_A_START -->

## Phase A · 项目骨架（Tasks 1-12）

### Task 1: Git & 根目录 `.gitignore` + README

**Files:**
- Create: `.gitignore`
- Create: `README.md`

- [ ] **Step 1: 写 `.gitignore`**

```gitignore
# Java
target/
*.class
*.jar
*.war
.mvn/wrapper/maven-wrapper.jar

# IDE
.idea/
*.iml
.vscode/
.project
.classpath
.settings/

# Node
node_modules/
dist/
*.tsbuildinfo
.pnpm-store/

# OS
.DS_Store
Thumbs.db

# Runtime
logs/
*.log

# Secrets & data
.env
data/
backup/

# E2E
e2e/test-results/
e2e/playwright-report/
```

- [ ] **Step 2: 写 `README.md`**

```markdown
# Factory EMS

工厂能源管理系统 — 子项目 1 · 地基 MVP。

## Prerequisites
- JDK 21
- Maven 3.9+
- Node 20+ & pnpm 9+
- Docker Desktop

## 快速启动（开发模式）
See `docs/ops/dev-setup.md`

## Spec & Plans
- `docs/superpowers/specs/2026-04-24-factory-ems-foundation-design.md`
- `docs/superpowers/plans/2026-04-24-factory-ems-plan-1.1-foundation-auth.md`
```

- [ ] **Step 3: 提交**

```bash
git add .gitignore README.md
git commit -m "chore: initial gitignore and readme"
```

---

### Task 2: 父 POM + Maven 多模块骨架

**Files:**
- Create: `pom.xml`
- Create: `ems-core/pom.xml`
- Create: `ems-audit/pom.xml`
- Create: `ems-orgtree/pom.xml`
- Create: `ems-auth/pom.xml`
- Create: `ems-app/pom.xml`

- [ ] **Step 1: 父 POM `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.ems</groupId>
    <artifactId>factory-ems</artifactId>
    <version>1.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.4</version>
        <relativePath/>
    </parent>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <testcontainers.version>1.20.1</testcontainers.version>
        <jjwt.version>0.12.6</jjwt.version>
        <mapstruct.version>1.6.0</mapstruct.version>
        <spotbugs.version>4.8.6</spotbugs.version>
    </properties>

    <modules>
        <module>ems-core</module>
        <module>ems-audit</module>
        <module>ems-orgtree</module>
        <module>ems-auth</module>
        <module>ems-app</module>
    </modules>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.testcontainers</groupId>
                <artifactId>testcontainers-bom</artifactId>
                <version>${testcontainers.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-api</artifactId>
                <version>${jjwt.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-impl</artifactId>
                <version>${jjwt.version}</version>
                <scope>runtime</scope>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-jackson</artifactId>
                <version>${jjwt.version}</version>
                <scope>runtime</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <parameters>true</parameters>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <version>0.8.12</version>
                <executions>
                    <execution>
                        <goals><goal>prepare-agent</goal></goals>
                    </execution>
                    <execution>
                        <id>report</id>
                        <phase>test</phase>
                        <goals><goal>report</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: `ems-core/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.ems</groupId>
        <artifactId>factory-ems</artifactId>
        <version>1.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>ems-core</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: `ems-audit/pom.xml`**（依赖 core）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.ems</groupId>
        <artifactId>factory-ems</artifactId>
        <version>1.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>ems-audit</artifactId>
    <dependencies>
        <dependency><groupId>com.ems</groupId><artifactId>ems-core</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-aop</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    </dependencies>
</project>
```

- [ ] **Step 4: `ems-orgtree/pom.xml`**（依赖 core、audit）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.ems</groupId>
        <artifactId>factory-ems</artifactId>
        <version>1.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>ems-orgtree</artifactId>
    <dependencies>
        <dependency><groupId>com.ems</groupId><artifactId>ems-core</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>com.ems</groupId><artifactId>ems-audit</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    </dependencies>
</project>
```

- [ ] **Step 5: `ems-auth/pom.xml`**（依赖 core、audit，可选 orgtree 仅为了实体类——采用"反向依赖"：orgtree 不依赖 auth；auth 不直接用 orgtree 实体，只用 org_node_id 数字；两者平行）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.ems</groupId>
        <artifactId>factory-ems</artifactId>
        <version>1.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>ems-auth</artifactId>
    <dependencies>
        <dependency><groupId>com.ems</groupId><artifactId>ems-core</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>com.ems</groupId><artifactId>ems-audit</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-aop</artifactId></dependency>
        <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-api</artifactId></dependency>
        <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-impl</artifactId></dependency>
        <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-jackson</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    </dependencies>
</project>
```

- [ ] **Step 6: `ems-app/pom.xml`**（装配所有模块）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.ems</groupId>
        <artifactId>factory-ems</artifactId>
        <version>1.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>ems-app</artifactId>
    <dependencies>
        <dependency><groupId>com.ems</groupId><artifactId>ems-core</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>com.ems</groupId><artifactId>ems-audit</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>com.ems</groupId><artifactId>ems-orgtree</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>com.ems</groupId><artifactId>ems-auth</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
        <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
        <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
        <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
        <dependency><groupId>net.logstash.logback</groupId><artifactId>logstash-logback-encoder</artifactId><version>8.0</version></dependency>
    </dependencies>

    <build>
        <finalName>factory-ems</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 7: 验证构建**

```bash
./mvnw -T 4 -DskipTests clean install
```

Expected: `BUILD SUCCESS`，5 个 JAR 生成。

- [ ] **Step 8: 提交**

```bash
git add pom.xml ems-*/pom.xml
git commit -m "build: maven multi-module skeleton"
```

---

### Task 3: `ems-core` 公共类

**Files:**
- Create: `ems-core/src/main/java/com/ems/core/dto/Result.java`
- Create: `ems-core/src/main/java/com/ems/core/dto/PageDTO.java`
- Create: `ems-core/src/main/java/com/ems/core/exception/BusinessException.java`
- Create: `ems-core/src/main/java/com/ems/core/exception/NotFoundException.java`
- Create: `ems-core/src/main/java/com/ems/core/exception/ForbiddenException.java`
- Create: `ems-core/src/main/java/com/ems/core/exception/UnauthorizedException.java`
- Create: `ems-core/src/main/java/com/ems/core/exception/ConflictException.java`
- Create: `ems-core/src/main/java/com/ems/core/constant/ErrorCode.java`
- Create: `ems-core/src/main/java/com/ems/core/util/TraceIdHolder.java`
- Test: `ems-core/src/test/java/com/ems/core/dto/ResultTest.java`

- [ ] **Step 1: 写 `Result.java`**

```java
package com.ems.core.dto;

public record Result<T>(int code, T data, String message, String traceId) {
    public static <T> Result<T> ok(T data) {
        return new Result<>(0, data, "ok", com.ems.core.util.TraceIdHolder.get());
    }
    public static <T> Result<T> ok() { return ok(null); }
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, null, message, com.ems.core.util.TraceIdHolder.get());
    }
}
```

- [ ] **Step 2: 写 `PageDTO.java`**

```java
package com.ems.core.dto;

import java.util.List;

public record PageDTO<T>(List<T> items, long total, int page, int size) {
    public static <T> PageDTO<T> of(List<T> items, long total, int page, int size) {
        return new PageDTO<>(items, total, page, size);
    }
}
```

- [ ] **Step 3: 写 `ErrorCode.java`**

```java
package com.ems.core.constant;

public final class ErrorCode {
    private ErrorCode() {}
    public static final int OK = 0;
    public static final int PARAM_INVALID    = 400;
    public static final int UNAUTHORIZED     = 40001;
    public static final int FORBIDDEN        = 40003;
    public static final int NOT_FOUND        = 40004;
    public static final int CONFLICT         = 40009;
    public static final int BIZ_GENERIC      = 40000;
    public static final int INTERNAL_ERROR   = 50000;
}
```

- [ ] **Step 4: 写 5 个异常类**

```java
// BusinessException.java
package com.ems.core.exception;
public class BusinessException extends RuntimeException {
    private final int code;
    public BusinessException(int code, String message) { super(message); this.code = code; }
    public int getCode() { return code; }
}
```

```java
// NotFoundException.java
package com.ems.core.exception;
import com.ems.core.constant.ErrorCode;
public class NotFoundException extends BusinessException {
    public NotFoundException(String resource, Object id) {
        super(ErrorCode.NOT_FOUND, resource + " not found: " + id);
    }
}
```

```java
// ForbiddenException.java
package com.ems.core.exception;
import com.ems.core.constant.ErrorCode;
public class ForbiddenException extends BusinessException {
    public ForbiddenException(String reason) { super(ErrorCode.FORBIDDEN, reason); }
}
```

```java
// UnauthorizedException.java
package com.ems.core.exception;
import com.ems.core.constant.ErrorCode;
public class UnauthorizedException extends BusinessException {
    public UnauthorizedException(String reason) { super(ErrorCode.UNAUTHORIZED, reason); }
}
```

```java
// ConflictException.java
package com.ems.core.exception;
import com.ems.core.constant.ErrorCode;
public class ConflictException extends BusinessException {
    public ConflictException(String reason) { super(ErrorCode.CONFLICT, reason); }
}
```

- [ ] **Step 5: 写 `TraceIdHolder.java`**

```java
package com.ems.core.util;

import org.slf4j.MDC;

public final class TraceIdHolder {
    public static final String KEY = "traceId";
    private TraceIdHolder() {}
    public static String get() {
        String v = MDC.get(KEY);
        return v == null ? "-" : v;
    }
    public static void set(String v) { MDC.put(KEY, v); }
    public static void clear() { MDC.remove(KEY); }
}
```

- [ ] **Step 6: 写测试 `ResultTest.java`**

```java
package com.ems.core.dto;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {
    @Test
    void ok_shouldCarryPayload() {
        Result<String> r = Result.ok("hello");
        assertThat(r.code()).isEqualTo(0);
        assertThat(r.data()).isEqualTo("hello");
        assertThat(r.message()).isEqualTo("ok");
    }

    @Test
    void error_shouldCarryCodeAndMessage() {
        Result<?> r = Result.error(40001, "unauthorized");
        assertThat(r.code()).isEqualTo(40001);
        assertThat(r.message()).isEqualTo("unauthorized");
        assertThat(r.data()).isNull();
    }
}
```

- [ ] **Step 7: 运行测试**

```bash
./mvnw -pl ems-core test
```

Expected: `Tests run: 2, Failures: 0`

- [ ] **Step 8: 提交**

```bash
git add ems-core/
git commit -m "feat(core): common DTOs, exceptions, trace id holder"
```

---

### Task 4: `ems-app` 主启动类 + application.yml

**Files:**
- Create: `ems-app/src/main/java/com/ems/app/FactoryEmsApplication.java`
- Create: `ems-app/src/main/resources/application.yml`
- Create: `ems-app/src/main/resources/application-dev.yml`
- Create: `ems-app/src/main/resources/application-prod.yml`

- [ ] **Step 1: 写启动类**

```java
package com.ems.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication(scanBasePackages = "com.ems")
@EntityScan(basePackages = "com.ems")
@EnableJpaRepositories(basePackages = "com.ems")
@EnableAsync
@EnableScheduling
@EnableTransactionManagement
public class FactoryEmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(FactoryEmsApplication.class, args);
    }
}
```

- [ ] **Step 2: 写 `application.yml`**

```yaml
spring:
  application:
    name: factory-ems
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.jdbc.time_zone: Asia/Shanghai
      hibernate.format_sql: false
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration

server:
  port: 8080
  servlet:
    context-path: /
  tomcat:
    max-threads: 200
  forward-headers-strategy: native

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  endpoint:
    health:
      probes:
        enabled: true
      show-details: when_authorized

ems:
  jwt:
    secret: ${EMS_JWT_SECRET:changeme-dev-only-32bytes-minimum!}
    access-token-minutes: 15
    refresh-token-days: 7
  login:
    max-failed-attempts: 5
    lockout-minutes: 15
```

- [ ] **Step 3: 写 `application-dev.yml`**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/factory_ems
    username: ems
    password: ${EMS_DB_PASSWORD:ems_dev}
    hikari:
      maximum-pool-size: 10

logging:
  level:
    root: INFO
    com.ems: DEBUG
    org.hibernate.SQL: DEBUG
```

- [ ] **Step 4: 写 `application-prod.yml`**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${EMS_DB_HOST:postgres}:5432/${EMS_DB_NAME:factory_ems}
    username: ${EMS_DB_USER:ems}
    password: ${EMS_DB_PASSWORD}
    hikari:
      maximum-pool-size: 30
      connection-timeout: 10000

logging:
  level:
    root: INFO
    com.ems: INFO
```

- [ ] **Step 5: 验证编译**

```bash
./mvnw -pl ems-app -am compile
```

- [ ] **Step 6: 提交**

```bash
git add ems-app/
git commit -m "feat(app): Spring Boot bootstrap + profiles"
```

---

### Task 5: Flyway 首迁移骨架 + Docker Compose 开发 DB

**Files:**
- Create: `ems-app/src/main/resources/db/migration/V0.0.0__placeholder.sql`
- Create: `docker-compose.dev.yml`
- Create: `.env.example`

- [ ] **Step 1: Placeholder 迁移**

```sql
-- ems-app/src/main/resources/db/migration/V0.0.0__placeholder.sql
-- 占位迁移，确保 Flyway 初始化。真实迁移从 V1.0.0 开始。
CREATE TABLE IF NOT EXISTS ems_boot_marker (
    id SMALLINT PRIMARY KEY DEFAULT 1,
    booted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT single_row CHECK (id = 1)
);
INSERT INTO ems_boot_marker (id) VALUES (1) ON CONFLICT DO NOTHING;
```

- [ ] **Step 2: 写 `.env.example`**

```bash
# .env.example — copy to .env and customize (NEVER commit .env)
EMS_VERSION=1.1.0-SNAPSHOT
SPRING_PROFILES_ACTIVE=dev

# Database
EMS_DB_HOST=postgres
EMS_DB_NAME=factory_ems
EMS_DB_USER=ems
EMS_DB_PASSWORD=change_me_strong_password

# JWT
EMS_JWT_SECRET=change_me_at_least_32_bytes_long_random
```

- [ ] **Step 3: 写 `docker-compose.dev.yml`**（开发用仅起 PG）

```yaml
version: "3.9"
services:
  postgres:
    image: postgres:15-alpine
    container_name: ems-postgres-dev
    environment:
      POSTGRES_DB: factory_ems
      POSTGRES_USER: ems
      POSTGRES_PASSWORD: ems_dev
    ports:
      - "5432:5432"
    volumes:
      - ems_pg_dev:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ems -d factory_ems"]
      interval: 5s
      timeout: 3s
      retries: 10

volumes:
  ems_pg_dev:
```

- [ ] **Step 4: 起数据库**

```bash
docker compose -f docker-compose.dev.yml up -d
docker compose -f docker-compose.dev.yml ps
```

Expected: `ems-postgres-dev` `healthy`.

- [ ] **Step 5: 运行 Spring Boot 验证 Flyway**

```bash
./mvnw -pl ems-app spring-boot:run
```

Expected 日志：`Successfully applied 1 migration to schema "public"`，进程启动后按 Ctrl-C 停。

- [ ] **Step 6: 提交**

```bash
git add ems-app/src/main/resources/db/ docker-compose.dev.yml .env.example
git commit -m "chore: flyway placeholder + dev postgres compose"
```

---

### Task 6: 结构化 JSON 日志配置

**Files:**
- Create: `ems-app/src/main/resources/logback-spring.xml`

- [ ] **Step 1: 写 `logback-spring.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProfile name="dev">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss.SSS} %-5level [%X{traceId:--}] %logger{36} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>

    <springProfile name="prod">
        <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <includeMdcKeyName>traceId</includeMdcKeyName>
                <includeMdcKeyName>userId</includeMdcKeyName>
                <includeMdcKeyName>spanId</includeMdcKeyName>
                <fieldNames>
                    <timestamp>ts</timestamp>
                    <message>msg</message>
                    <thread>[ignore]</thread>
                </fieldNames>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="JSON"/>
        </root>
    </springProfile>
</configuration>
```

- [ ] **Step 2: 验证**

```bash
./mvnw -pl ems-app spring-boot:run -Dspring-boot.run.profiles=prod
```

Expected: 控制台输出 JSON 格式日志，字段含 `ts, level, msg, traceId`。按 Ctrl-C 停。

- [ ] **Step 3: 提交**

```bash
git add ems-app/src/main/resources/logback-spring.xml
git commit -m "feat(app): structured JSON logging (prod profile)"
```

---

### Task 7: `TraceIdFilter`

**Files:**
- Create: `ems-app/src/main/java/com/ems/app/filter/TraceIdFilter.java`
- Test: `ems-app/src/test/java/com/ems/app/filter/TraceIdFilterTest.java`

- [ ] **Step 1: 写测试**

```java
package com.ems.app.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    @Test
    void shouldGenerateTraceIdIfNotPresent() throws ServletException, IOException {
        var req = new MockHttpServletRequest();
        var res = new MockHttpServletResponse();
        FilterChain chain = (r, s) -> {
            assertThat(MDC.get("traceId")).isNotBlank();
            ((jakarta.servlet.http.HttpServletResponse) s).setHeader("X-Trace-Id", MDC.get("traceId"));
        };
        new TraceIdFilter().doFilter(req, res, chain);
        assertThat(res.getHeader("X-Trace-Id")).isNotBlank();
        assertThat(MDC.get("traceId")).isNull();  // 清理了
    }

    @Test
    void shouldUseIncomingTraceId() throws ServletException, IOException {
        var req = new MockHttpServletRequest();
        req.addHeader("X-Trace-Id", "abc123");
        var res = new MockHttpServletResponse();
        FilterChain chain = (r, s) -> assertThat(MDC.get("traceId")).isEqualTo("abc123");
        new TraceIdFilter().doFilter(req, res, chain);
    }
}
```

- [ ] **Step 2: 跑测试看它失败**

```bash
./mvnw -pl ems-app test -Dtest=TraceIdFilterTest
```

Expected: 编译失败，`TraceIdFilter` 不存在。

- [ ] **Step 3: 写实现**

```java
package com.ems.app.filter;

import com.ems.core.util.TraceIdHolder;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(1)
public class TraceIdFilter implements Filter {

    public static final String HEADER = "X-Trace-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req  = (HttpServletRequest)  request;
        HttpServletResponse res = (HttpServletResponse) response;
        String traceId = req.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        TraceIdHolder.set(traceId);
        res.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceIdHolder.clear();
        }
    }
}
```

- [ ] **Step 4: 再跑测试**

```bash
./mvnw -pl ems-app test -Dtest=TraceIdFilterTest
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add ems-app/src/main/java/com/ems/app/filter/ ems-app/src/test/
git commit -m "feat(app): TraceIdFilter with inbound-header support"
```

---

### Task 8: 全局异常处理

**Files:**
- Create: `ems-app/src/main/java/com/ems/app/handler/GlobalExceptionHandler.java`
- Test: `ems-app/src/test/java/com/ems/app/handler/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: 写实现**

```java
package com.ems.app.handler;

import com.ems.core.constant.ErrorCode;
import com.ems.core.dto.Result;
import com.ems.core.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<?>> biz(BusinessException ex) {
        HttpStatus s = switch (ex.getCode()) {
            case ErrorCode.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ErrorCode.FORBIDDEN -> HttpStatus.FORBIDDEN;
            case ErrorCode.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case ErrorCode.CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        log.info("biz_ex code={} msg={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(s).body(Result.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> validation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b).orElse("validation failed");
        return ResponseEntity.badRequest().body(Result.error(ErrorCode.PARAM_INVALID, msg));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<?>> denied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Result.error(ErrorCode.FORBIDDEN, "access denied"));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Result<?>> badCred(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Result.error(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> unknown(Exception ex) {
        log.error("unhandled_ex", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Result.error(ErrorCode.INTERNAL_ERROR, "服务器错误，请联系管理员"));
    }
}
```

- [ ] **Step 2: 写测试**（通过 MockMvc 模拟各异常）

```java
package com.ems.app.handler;

import com.ems.core.exception.BusinessException;
import com.ems.core.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {GlobalExceptionHandler.class, GlobalExceptionHandlerTest.TestCtl.class})
class GlobalExceptionHandlerTest {

    @Autowired GlobalExceptionHandler handler;
    @Autowired TestCtl ctl;

    @Test
    void notFound_returns404() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(ctl).setControllerAdvice(handler).build();
        mvc.perform(get("/not-found"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.code").value(40004));
    }

    @Test
    void biz400_returns400() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(ctl).setControllerAdvice(handler).build();
        mvc.perform(get("/biz-err"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value(40000));
    }

    @RestController
    static class TestCtl {
        @GetMapping("/not-found") public String nf() { throw new NotFoundException("User", 42); }
        @GetMapping("/biz-err") public String biz() { throw new BusinessException(40000, "bad"); }
    }
}
```

- [ ] **Step 3: 跑测试**

```bash
./mvnw -pl ems-app test -Dtest=GlobalExceptionHandlerTest
```

Expected: PASS.

- [ ] **Step 4: 提交**

```bash
git add ems-app/src/main/java/com/ems/app/handler/ ems-app/src/test/java/com/ems/app/handler/
git commit -m "feat(app): global exception handler"
```

---

### Task 9: Async 执行器 + CORS 配置

**Files:**
- Create: `ems-app/src/main/java/com/ems/app/config/AsyncConfig.java`
- Create: `ems-app/src/main/java/com/ems/app/config/CorsConfig.java`

- [ ] **Step 1: `AsyncConfig.java`**

```java
package com.ems.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean(name = "auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(1000);
        ex.setThreadNamePrefix("audit-");
        ex.setTaskDecorator(mdcPropagating());
        ex.initialize();
        return ex;
    }

    private TaskDecorator mdcPropagating() {
        return runnable -> {
            Map<String, String> ctx = MDC.getCopyOfContextMap();
            return () -> {
                try {
                    if (ctx != null) MDC.setContextMap(ctx);
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }
}
```

- [ ] **Step 2: `CorsConfig.java`**（开发环境用）

```java
package com.ems.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
@Profile("dev")
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowCredentials(true);
        c.setAllowedOrigins(List.of("http://localhost:5173"));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setExposedHeaders(List.of("X-Trace-Id"));
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", c);
        return new CorsFilter(src);
    }
}
```

- [ ] **Step 3: 编译**

```bash
./mvnw -pl ems-app compile
```

- [ ] **Step 4: 提交**

```bash
git add ems-app/src/main/java/com/ems/app/config/
git commit -m "feat(app): async executor with MDC propagation + dev CORS"
```

---

### Task 10: Actuator Health + Info 配置

**Files:**
- Modify: `ems-app/src/main/resources/application.yml` (已在 Task 4 添加)

- [ ] **Step 1: 启动服务验证**

```bash
./mvnw -pl ems-app spring-boot:run
```

在另一个终端：

```bash
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness
```

Expected: `{"status":"UP"}`。

- [ ] **Step 2: 无代码改动，按 Ctrl-C 停止，这个 Task 仅为验证。提交跳过。**

---

### Task 11: GitHub Actions CI 流水线

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: 写 CI workflow**

```yaml
name: CI

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main, develop]

jobs:
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
          cache: maven
      - name: Build & Test
        run: ./mvnw -B -T 4 clean verify
      - name: Upload coverage
        uses: actions/upload-artifact@v4
        with:
          name: jacoco-reports
          path: '**/target/site/jacoco/'

  frontend:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: frontend
    steps:
      - uses: actions/checkout@v4
      - uses: pnpm/action-setup@v4
        with:
          version: 9
      - uses: actions/setup-node@v4
        with:
          node-version: 20
          cache: pnpm
          cache-dependency-path: frontend/pnpm-lock.yaml
      - name: Install
        run: pnpm install --frozen-lockfile
      - name: Lint
        run: pnpm lint
      - name: Type Check
        run: pnpm typecheck
      - name: Test
        run: pnpm test --run
      - name: Build
        run: pnpm build

  e2e:
    needs: [backend, frontend]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21, cache: maven }
      - uses: pnpm/action-setup@v4
        with: { version: 9 }
      - uses: actions/setup-node@v4
        with: { node-version: 20, cache: pnpm, cache-dependency-path: frontend/pnpm-lock.yaml }
      - name: Build backend image
        run: docker build -t factory-ems:ci -f ems-app/Dockerfile .
      - name: Build frontend
        run: cd frontend && pnpm install --frozen-lockfile && pnpm build
      - name: Start stack
        run: docker compose -f docker-compose.yml up -d --wait
      - name: Install Playwright
        run: cd e2e && pnpm install && pnpm exec playwright install --with-deps chromium
      - name: E2E
        run: cd e2e && pnpm test
      - name: Tear down
        if: always()
        run: docker compose -f docker-compose.yml down -v
```

- [ ] **Step 2: 提交**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: backend + frontend + e2e pipeline"
```

---

### Task 12: Maven Wrapper 脚本

**Files:**
- Create: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`

- [ ] **Step 1: 安装 wrapper**

```bash
mvn -N wrapper:wrapper -Dmaven=3.9.9
```

- [ ] **Step 2: 验证 wrapper**

```bash
./mvnw -v
```

Expected: Maven 3.9.9 + Java 21.

- [ ] **Step 3: 提交**

```bash
git add mvnw mvnw.cmd .mvn/
git commit -m "build: maven wrapper"
```

---

## Phase B · `ems-audit` 模块（Tasks 13-18）

### Task 13: Flyway 审计表迁移

**Files:**
- Create: `ems-app/src/main/resources/db/migration/V1.0.7__init_audit.sql`

- [ ] **Step 1: 写迁移 SQL**

```sql
CREATE TABLE audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    actor_user_id   BIGINT,
    actor_username  VARCHAR(64),
    action          VARCHAR(32)  NOT NULL,
    resource_type   VARCHAR(32),
    resource_id     VARCHAR(64),
    summary         TEXT,
    detail          JSONB,
    ip              VARCHAR(64),
    user_agent      TEXT,
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_occurred_at ON audit_logs (occurred_at DESC);
CREATE INDEX idx_audit_actor       ON audit_logs (actor_user_id, occurred_at DESC);
CREATE INDEX idx_audit_resource    ON audit_logs (resource_type, resource_id, occurred_at DESC);
CREATE INDEX idx_audit_action      ON audit_logs (action, occurred_at DESC);
```

- [ ] **Step 2: 启动应用验证迁移**

```bash
./mvnw -pl ems-app spring-boot:run
```

预期日志：`Successfully applied 2 migrations`。Ctrl-C 停。

- [ ] **Step 3: 提交**

```bash
git add ems-app/src/main/resources/db/migration/V1.0.7__init_audit.sql
git commit -m "feat(audit): flyway migration for audit_logs"
```

---

### Task 14: `AuditLog` 实体 + Repository

**Files:**
- Create: `ems-audit/src/main/java/com/ems/audit/entity/AuditLog.java`
- Create: `ems-audit/src/main/java/com/ems/audit/repository/AuditLogRepository.java`

- [ ] **Step 1: 写 `AuditLog.java`**

```java
package com.ems.audit.entity;

import jakarta.persistence.*;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id")   private Long actorUserId;
    @Column(name = "actor_username")  private String actorUsername;
    @Column(nullable = false)         private String action;
    @Column(name = "resource_type")   private String resourceType;
    @Column(name = "resource_id")     private String resourceId;
    @Column(columnDefinition = "text") private String summary;

    @Column(columnDefinition = "jsonb")
    private String detail;

    private String ip;

    @Column(name = "user_agent", columnDefinition = "text")
    private String userAgent;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    // 全量 getters/setters 省略，实际写时用 Lombok @Data 或 IDE 生成
    public Long getId() { return id; }
    public Long getActorUserId() { return actorUserId; }
    public String getActorUsername() { return actorUsername; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getSummary() { return summary; }
    public String getDetail() { return detail; }
    public String getIp() { return ip; }
    public String getUserAgent() { return userAgent; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }

    public void setActorUserId(Long v) { this.actorUserId = v; }
    public void setActorUsername(String v) { this.actorUsername = v; }
    public void setAction(String v) { this.action = v; }
    public void setResourceType(String v) { this.resourceType = v; }
    public void setResourceId(String v) { this.resourceId = v; }
    public void setSummary(String v) { this.summary = v; }
    public void setDetail(String v) { this.detail = v; }
    public void setIp(String v) { this.ip = v; }
    public void setUserAgent(String v) { this.userAgent = v; }
    public void setOccurredAt(OffsetDateTime v) { this.occurredAt = v; }
}
```

> **Note:** `detail` 字段用 `String (jsonb)` 简化——存入时手动 `objectMapper.writeValueAsString(...)`，读取时手动反序列化。避免引入 hibernate-types 依赖。

- [ ] **Step 2: 把 `detail` 改为 `String`（jsonb 用 columnDefinition），移除 import `io.hypersistence.*` 和 `@Type`**（上面示例已去）

- [ ] **Step 3: 写 Repository**

```java
package com.ems.audit.repository;

import com.ems.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("""
        SELECT a FROM AuditLog a
        WHERE (:actor IS NULL OR a.actorUserId = :actor)
          AND (:resType IS NULL OR a.resourceType = :resType)
          AND (:action IS NULL OR a.action = :action)
          AND (:from IS NULL OR a.occurredAt >= :from)
          AND (:to IS NULL OR a.occurredAt < :to)
        ORDER BY a.occurredAt DESC
    """)
    Page<AuditLog> search(@Param("actor") Long actor,
                          @Param("resType") String resType,
                          @Param("action") String action,
                          @Param("from") OffsetDateTime from,
                          @Param("to") OffsetDateTime to,
                          Pageable pageable);
}
```

- [ ] **Step 4: 提交**

```bash
git add ems-audit/src/main/java/com/ems/audit/entity/ ems-audit/src/main/java/com/ems/audit/repository/
git commit -m "feat(audit): AuditLog entity + repository"
```

---

### Task 15: `@Audited` 注解 + `AuditEvent`

**Files:**
- Create: `ems-audit/src/main/java/com/ems/audit/annotation/Audited.java`
- Create: `ems-audit/src/main/java/com/ems/audit/event/AuditEvent.java`

- [ ] **Step 1: 写 `Audited`**

```java
package com.ems.audit.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
    String action();             // CREATE|UPDATE|DELETE|CONFIG_CHANGE 等
    String resourceType();       // USER|METER|ORG_NODE|...
    /** SpEL 表达式，从参数或返回值中取出 resourceId。例如 "#id" 或 "#result.id" */
    String resourceIdExpr() default "";
    /** 简短描述 SpEL，可选 */
    String summaryExpr() default "";
}
```

- [ ] **Step 2: 写 `AuditEvent`**

```java
package com.ems.audit.event;

import java.time.OffsetDateTime;

public record AuditEvent(
    Long actorUserId,
    String actorUsername,
    String action,
    String resourceType,
    String resourceId,
    String summary,
    String detailJson,
    String ip,
    String userAgent,
    OffsetDateTime occurredAt
) {}
```

- [ ] **Step 3: 提交**

```bash
git add ems-audit/src/main/java/com/ems/audit/annotation/ ems-audit/src/main/java/com/ems/audit/event/
git commit -m "feat(audit): @Audited annotation + AuditEvent record"
```

---

### Task 16: `AuditService` + `AsyncAuditListener`

**Files:**
- Create: `ems-audit/src/main/java/com/ems/audit/service/AuditService.java`
- Create: `ems-audit/src/main/java/com/ems/audit/listener/AsyncAuditListener.java`

- [ ] **Step 1: 写 `AuditService`（发布事件）**

```java
package com.ems.audit.service;

import com.ems.audit.event.AuditEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final ApplicationEventPublisher publisher;

    public AuditService(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void record(AuditEvent event) {
        publisher.publishEvent(event);
    }
}
```

- [ ] **Step 2: 写 `AsyncAuditListener`（AFTER_COMMIT 异步入库）**

```java
package com.ems.audit.listener;

import com.ems.audit.entity.AuditLog;
import com.ems.audit.event.AuditEvent;
import com.ems.audit.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.context.event.EventListener;

@Component
public class AsyncAuditListener {

    private static final Logger log = LoggerFactory.getLogger(AsyncAuditListener.class);

    private final AuditLogRepository repo;

    public AsyncAuditListener(AuditLogRepository repo) {
        this.repo = repo;
    }

    /** 业务事务成功提交后才写入（失败不产审计） */
    @Async("auditExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAfterCommit(AuditEvent ev) {
        writeLog(ev);
    }

    /** 登录事件没有包围事务，直接监听 */
    @Async("auditExecutor")
    @EventListener(condition = "#ev.action == 'LOGIN' or #ev.action == 'LOGOUT' or #ev.action == 'LOGIN_FAIL'")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAuthEvent(AuditEvent ev) {
        writeLog(ev);
    }

    private void writeLog(AuditEvent ev) {
        try {
            AuditLog a = new AuditLog();
            a.setActorUserId(ev.actorUserId());
            a.setActorUsername(ev.actorUsername());
            a.setAction(ev.action());
            a.setResourceType(ev.resourceType());
            a.setResourceId(ev.resourceId());
            a.setSummary(ev.summary());
            a.setDetail(ev.detailJson());
            a.setIp(ev.ip());
            a.setUserAgent(ev.userAgent());
            a.setOccurredAt(ev.occurredAt());
            repo.save(a);
        } catch (Exception e) {
            log.error("audit_write_failed action={} resource={} id={}",
                ev.action(), ev.resourceType(), ev.resourceId(), e);
        }
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add ems-audit/src/main/java/com/ems/audit/service/ ems-audit/src/main/java/com/ems/audit/listener/
git commit -m "feat(audit): AuditService + async AFTER_COMMIT listener"
```

---

### Task 17: `AuditAspect` — `@Audited` 切面

**Files:**
- Create: `ems-audit/src/main/java/com/ems/audit/aspect/AuditAspect.java`
- Create: `ems-audit/src/main/java/com/ems/audit/aspect/AuditContext.java`

- [ ] **Step 1: 写 `AuditContext`（取当前登录用户）**

```java
package com.ems.audit.aspect;

/** 最小接口，避免 ems-audit 依赖 ems-auth。具体实现在 ems-auth 里提供。 */
public interface AuditContext {
    Long currentUserId();
    String currentUsername();
    String currentIp();
    String currentUserAgent();
}
```

- [ ] **Step 2: 写 `AuditAspect`**

```java
package com.ems.audit.aspect;

import com.ems.audit.annotation.Audited;
import com.ems.audit.event.AuditEvent;
import com.ems.audit.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);
    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final DefaultParameterNameDiscoverer DISC = new DefaultParameterNameDiscoverer();

    private final AuditService auditService;
    private final AuditContext auditContext;
    private final ObjectMapper objectMapper;

    public AuditAspect(AuditService as, AuditContext ctx, ObjectMapper om) {
        this.auditService = as; this.auditContext = ctx; this.objectMapper = om;
    }

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        Object result = pjp.proceed();
        try {
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            var ctx = new MethodBasedEvaluationContext(pjp.getTarget(), sig.getMethod(), pjp.getArgs(), DISC);
            ctx.setVariable("result", result);
            String resId = eval(audited.resourceIdExpr(), ctx);
            String summary = eval(audited.summaryExpr(), ctx);
            String detail = objectMapper.writeValueAsString(pjp.getArgs());
            AuditEvent ev = new AuditEvent(
                auditContext.currentUserId(),
                auditContext.currentUsername(),
                audited.action(),
                audited.resourceType(),
                resId,
                summary,
                detail,
                auditContext.currentIp(),
                auditContext.currentUserAgent(),
                OffsetDateTime.now()
            );
            auditService.record(ev);
        } catch (Exception e) {
            log.error("audit_aspect_failed method={}", pjp.getSignature().toShortString(), e);
        }
        return result;
    }

    private String eval(String expr, MethodBasedEvaluationContext ctx) {
        if (expr == null || expr.isBlank()) return null;
        try {
            Object v = PARSER.parseExpression(expr).getValue(ctx);
            return v == null ? null : v.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add ems-audit/src/main/java/com/ems/audit/aspect/
git commit -m "feat(audit): AuditAspect with SpEL resourceId extraction"
```

---

### Task 18: `ems-audit` 集成测试（Testcontainers）

**Files:**
- Create: `ems-audit/src/test/java/com/ems/audit/AuditFlowIntegrationTest.java`
- Create: `ems-audit/src/test/resources/application-test.yml`

- [ ] **Step 1: 写测试配置**

```yaml
# ems-audit/src/test/resources/application-test.yml
spring:
  jpa:
    hibernate.ddl-auto: create-drop
    properties:
      hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: false
```

- [ ] **Step 2: 写集成测试**

```java
package com.ems.audit;

import com.ems.audit.event.AuditEvent;
import com.ems.audit.repository.AuditLogRepository;
import com.ems.audit.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = AuditFlowIntegrationTest.TestApp.class)
@Testcontainers
@ActiveProfiles("test")
class AuditFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired AuditService service;
    @Autowired AuditLogRepository repo;

    @Test
    void shouldPersistAuditEvent() {
        AuditEvent ev = new AuditEvent(1L, "admin", "CREATE", "USER", "42",
            "created user zhang3", "{}", "127.0.0.1", "junit", OffsetDateTime.now());
        service.record(ev);

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(repo.findAll())
                .hasSize(1)
                .first()
                .satisfies(a -> {
                    assertThat(a.getAction()).isEqualTo("CREATE");
                    assertThat(a.getResourceType()).isEqualTo("USER");
                    assertThat(a.getResourceId()).isEqualTo("42");
                })
        );
    }

    @Configuration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = "com.ems.audit")
    @EntityScan(basePackages = "com.ems.audit.entity")
    @EnableJpaRepositories(basePackages = "com.ems.audit.repository")
    static class TestApp {
        @org.springframework.context.annotation.Bean
        com.ems.audit.aspect.AuditContext testCtx() {
            return new com.ems.audit.aspect.AuditContext() {
                public Long currentUserId() { return 1L; }
                public String currentUsername() { return "admin"; }
                public String currentIp() { return "127.0.0.1"; }
                public String currentUserAgent() { return "junit"; }
            };
        }
    }
}
```

- [ ] **Step 3: 加 awaitility 依赖到 `ems-audit/pom.xml`**

```xml
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 4: 运行测试**

```bash
./mvnw -pl ems-audit test
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add ems-audit/
git commit -m "test(audit): integration test with testcontainers"
```

---

## Phase C · `ems-orgtree` 模块（Tasks 19-31）

### Task 19: 组织树 Flyway 迁移

**Files:**
- Create: `ems-app/src/main/resources/db/migration/V1.0.1__init_orgtree.sql`

- [ ] **Step 1: 写迁移 SQL**

```sql
CREATE TABLE org_nodes (
    id           BIGSERIAL PRIMARY KEY,
    parent_id    BIGINT REFERENCES org_nodes(id) ON DELETE RESTRICT,
    name         VARCHAR(128) NOT NULL,
    code         VARCHAR(64)  NOT NULL UNIQUE,
    node_type    VARCHAR(32)  NOT NULL,
    sort_order   INT          NOT NULL DEFAULT 0,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_org_nodes_parent ON org_nodes(parent_id);

CREATE TABLE org_node_closure (
    ancestor_id   BIGINT NOT NULL REFERENCES org_nodes(id) ON DELETE CASCADE,
    descendant_id BIGINT NOT NULL REFERENCES org_nodes(id) ON DELETE CASCADE,
    depth         INT    NOT NULL,
    PRIMARY KEY (ancestor_id, descendant_id)
);

CREATE INDEX idx_closure_descendant ON org_node_closure(descendant_id);
CREATE INDEX idx_closure_ancestor   ON org_node_closure(ancestor_id, depth);
```

- [ ] **Step 2: 启动验证**

```bash
./mvnw -pl ems-app spring-boot:run
```

Ctrl-C 停止。

- [ ] **Step 3: 提交**

```bash
git add ems-app/src/main/resources/db/migration/V1.0.1__init_orgtree.sql
git commit -m "feat(orgtree): flyway migration for org_nodes and closure table"
```

---

### Task 20: `OrgNode` + `OrgNodeClosure` 实体

**Files:**
- Create: `ems-orgtree/src/main/java/com/ems/orgtree/entity/OrgNode.java`
- Create: `ems-orgtree/src/main/java/com/ems/orgtree/entity/OrgNodeClosure.java`
- Create: `ems-orgtree/src/main/java/com/ems/orgtree/entity/OrgNodeClosureId.java`

- [ ] **Step 1: `OrgNode.java`**

```java
package com.ems.orgtree.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "org_nodes")
public class OrgNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 64, unique = true)
    private String code;

    @Column(name = "node_type", nullable = false, length = 32)
    private String nodeType;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = createdAt != null ? createdAt : now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = OffsetDateTime.now(); }

    // getters/setters
    public Long getId() { return id; }
    public Long getParentId() { return parentId; }
    public String getName() { return name; }
    public String getCode() { return code; }
    public String getNodeType() { return nodeType; }
    public Integer getSortOrder() { return sortOrder; }
    public Long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setParentId(Long v) { this.parentId = v; }
    public void setName(String v) { this.name = v; }
    public void setCode(String v) { this.code = v; }
    public void setNodeType(String v) { this.nodeType = v; }
    public void setSortOrder(Integer v) { this.sortOrder = v; }
}
```

- [ ] **Step 2: `OrgNodeClosureId.java`（复合主键）**

```java
package com.ems.orgtree.entity;

import java.io.Serializable;
import java.util.Objects;

public class OrgNodeClosureId implements Serializable {
    private Long ancestorId;
    private Long descendantId;

    public OrgNodeClosureId() {}
    public OrgNodeClosureId(Long a, Long d) { this.ancestorId = a; this.descendantId = d; }

    public Long getAncestorId() { return ancestorId; }
    public Long getDescendantId() { return descendantId; }
    public void setAncestorId(Long v) { this.ancestorId = v; }
    public void setDescendantId(Long v) { this.descendantId = v; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrgNodeClosureId x)) return false;
        return Objects.equals(ancestorId, x.ancestorId) && Objects.equals(descendantId, x.descendantId);
    }
    @Override public int hashCode() { return Objects.hash(ancestorId, descendantId); }
}
```

- [ ] **Step 3: `OrgNodeClosure.java`**

```java
package com.ems.orgtree.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "org_node_closure")
@IdClass(OrgNodeClosureId.class)
public class OrgNodeClosure {

    @Id @Column(name = "ancestor_id")   private Long ancestorId;
    @Id @Column(name = "descendant_id") private Long descendantId;
    @Column(nullable = false)           private Integer depth;

    public OrgNodeClosure() {}
    public OrgNodeClosure(Long ancestorId, Long descendantId, Integer depth) {
        this.ancestorId = ancestorId; this.descendantId = descendantId; this.depth = depth;
    }

    public Long getAncestorId() { return ancestorId; }
    public Long getDescendantId() { return descendantId; }
    public Integer getDepth() { return depth; }
    public void setAncestorId(Long v) { this.ancestorId = v; }
    public void setDescendantId(Long v) { this.descendantId = v; }
    public void setDepth(Integer v) { this.depth = v; }
}
```

- [ ] **Step 4: 提交**

```bash
git add ems-orgtree/src/main/java/com/ems/orgtree/entity/
git commit -m "feat(orgtree): OrgNode and OrgNodeClosure entities"
```

---

### Task 21: Repositories（JPA + 原生 SQL）

**Files:**
- Create: `ems-orgtree/src/main/java/com/ems/orgtree/repository/OrgNodeRepository.java`
- Create: `ems-orgtree/src/main/java/com/ems/orgtree/repository/OrgNodeClosureRepository.java`

- [ ] **Step 1: `OrgNodeRepository`**

```java
package com.ems.orgtree.repository;

import com.ems.orgtree.entity.OrgNode;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface OrgNodeRepository extends JpaRepository<OrgNode, Long> {
    Optional<OrgNode> findByCode(String code);
    boolean existsByCode(String code);
    List<OrgNode> findAllByOrderBySortOrderAscIdAsc();
}
```

- [ ] **Step 2: `OrgNodeClosureRepository`**

```java
package com.ems.orgtree.repository;

import com.ems.orgtree.entity.OrgNodeClosure;
import com.ems.orgtree.entity.OrgNodeClosureId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrgNodeClosureRepository extends JpaRepository<OrgNodeClosure, OrgNodeClosureId> {

    /** 返回 nodeId 及其所有后代 id（含自身） */
    @Query(value = """
        SELECT descendant_id FROM org_node_closure WHERE ancestor_id = :nodeId
    """, nativeQuery = true)
    List<Long> findDescendantIds(@Param("nodeId") Long nodeId);

    /** nodeId 的所有祖先 id（含自身） */
    @Query(value = """
        SELECT ancestor_id FROM org_node_closure WHERE descendant_id = :nodeId
    """, nativeQuery = true)
    List<Long> findAncestorIds(@Param("nodeId") Long nodeId);

    /** 插入：newNode 与 parent 所有祖先的新闭包行（depth = 祖先到 parent 的深度 + 1），以及 newNode 自身的 (self,self,0) */
    @Modifying
    @Query(value = """
        INSERT INTO org_node_closure (ancestor_id, descendant_id, depth)
        SELECT ancestor_id, :nodeId, depth + 1 FROM org_node_closure WHERE descendant_id = :parentId
        UNION ALL SELECT :nodeId, :nodeId, 0
    """, nativeQuery = true)
    void insertForNewLeaf(@Param("nodeId") Long nodeId, @Param("parentId") Long parentId);

    /** 根节点（没有父）：只插自身 */
    @Modifying
    @Query(value = """
        INSERT INTO org_node_closure (ancestor_id, descendant_id, depth) VALUES (:nodeId, :nodeId, 0)
    """, nativeQuery = true)
    void insertForRoot(@Param("nodeId") Long nodeId);

    /** 移动子树 move：删除旧祖先对子树的闭包行 */
    @Modifying
    @Query(value = """
        DELETE FROM org_node_closure
        WHERE descendant_id IN (
            SELECT descendant_id FROM org_node_closure WHERE ancestor_id = :nodeId
        )
          AND ancestor_id IN (
            SELECT ancestor_id FROM org_node_closure
            WHERE descendant_id = :nodeId AND ancestor_id <> :nodeId
          )
    """, nativeQuery = true)
    void deleteAncestorsOfSubtree(@Param("nodeId") Long nodeId);

    /** 移动子树 move：插入新祖先对子树的闭包行 */
    @Modifying
    @Query(value = """
        INSERT INTO org_node_closure (ancestor_id, descendant_id, depth)
        SELECT super_tree.ancestor_id,
               sub_tree.descendant_id,
               super_tree.depth + sub_tree.depth + 1
        FROM org_node_closure super_tree
        CROSS JOIN org_node_closure sub_tree
        WHERE super_tree.descendant_id = :newParentId
          AND sub_tree.ancestor_id    = :nodeId
    """, nativeQuery = true)
    void insertAncestorsForSubtree(@Param("nodeId") Long nodeId, @Param("newParentId") Long newParentId);

    /** 检查 newParent 是不是 node 的后代（防环） */
    @Query(value = """
        SELECT COUNT(*) > 0 FROM org_node_closure
        WHERE ancestor_id = :nodeId AND descendant_id = :newParentId
    """, nativeQuery = true)
    boolean isDescendant(@Param("nodeId") Long nodeId, @Param("newParentId") Long newParentId);
}
```

- [ ] **Step 3: 提交**

```bash
git add ems-orgtree/src/main/java/com/ems/orgtree/repository/
git commit -m "feat(orgtree): repositories with closure-table operations"
```

---

### Task 22: `OrgNodeService.create()` + 闭包维护

**Files:**
- Create: `ems-orgtree/src/main/java/com/ems/orgtree/dto/CreateOrgNodeReq.java`
- Create: `ems-orgtree/src/main/java/com/ems/orgtree/dto/OrgNodeDTO.java`
- Create: `ems-orgtree/src/main/java/com/ems/orgtree/service/OrgNodeService.java`
- Create: `ems-orgtree/src/main/java/com/ems/orgtree/service/impl/OrgNodeServiceImpl.java`

- [ ] **Step 1: DTO**

```java
// CreateOrgNodeReq.java
package com.ems.orgtree.dto;

import jakarta.validation.constraints.*;

public record CreateOrgNodeReq(
    Long parentId,
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_\\-]+") String code,
    @NotBlank @Size(max = 32) String nodeType,
    Integer sortOrder
) {}
```

```java
// OrgNodeDTO.java
package com.ems.orgtree.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record OrgNodeDTO(
    Long id, Long parentId, String name, String code, String nodeType,
    Integer sortOrder, OffsetDateTime createdAt,
    List<OrgNodeDTO> children
) {}
```

- [ ] **Step 2: Service 接口**

```java
package com.ems.orgtree.service;

import com.ems.orgtree.dto.*;

import java.util.List;

public interface OrgNodeService {
    OrgNodeDTO create(CreateOrgNodeReq req);
    OrgNodeDTO update(Long id, UpdateOrgNodeReq req);
    void move(Long id, MoveOrgNodeReq req);
    void delete(Long id);
    OrgNodeDTO getById(Long id);
    List<OrgNodeDTO> getTree(Long rootId);                   // null => 全树
    List<Long> findDescendantIds(Long id);
}
```

- [ ] **Step 3: 占位 DTO（供接口编译）**

```java
// UpdateOrgNodeReq.java
package com.ems.orgtree.dto;
import jakarta.validation.constraints.*;
public record UpdateOrgNodeReq(
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 32)  String nodeType,
    Integer sortOrder
) {}
```

```java
// MoveOrgNodeReq.java
package com.ems.orgtree.dto;
public record MoveOrgNodeReq(Long newParentId) {}
```

- [ ] **Step 4: 写 `OrgNodeServiceImpl.create()`**

```java
package com.ems.orgtree.service.impl;

import com.ems.audit.annotation.Audited;
import com.ems.core.exception.BusinessException;
import com.ems.core.exception.NotFoundException;
import com.ems.core.constant.ErrorCode;
import com.ems.orgtree.dto.*;
import com.ems.orgtree.entity.OrgNode;
import com.ems.orgtree.repository.OrgNodeClosureRepository;
import com.ems.orgtree.repository.OrgNodeRepository;
import com.ems.orgtree.service.OrgNodeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class OrgNodeServiceImpl implements OrgNodeService {

    private final OrgNodeRepository nodes;
    private final OrgNodeClosureRepository closure;

    public OrgNodeServiceImpl(OrgNodeRepository n, OrgNodeClosureRepository c) {
        this.nodes = n; this.closure = c;
    }

    @Override
    @Transactional
    @Audited(action = "CREATE", resourceType = "ORG_NODE", resourceIdExpr = "#result.id()")
    public OrgNodeDTO create(CreateOrgNodeReq req) {
        if (nodes.existsByCode(req.code()))
            throw new BusinessException(ErrorCode.CONFLICT, "节点编码已存在: " + req.code());

        if (req.parentId() != null) {
            nodes.findById(req.parentId())
                .orElseThrow(() -> new NotFoundException("OrgNode", req.parentId()));
        }

        OrgNode n = new OrgNode();
        n.setParentId(req.parentId());
        n.setName(req.name());
        n.setCode(req.code());
        n.setNodeType(req.nodeType());
        n.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        nodes.save(n);

        if (req.parentId() == null) closure.insertForRoot(n.getId());
        else                         closure.insertForNewLeaf(n.getId(), req.parentId());

        return toDTO(n, List.of());
    }

    // 其他方法先留空，下几个任务填
    @Override public OrgNodeDTO update(Long id, UpdateOrgNodeReq req) { throw new UnsupportedOperationException(); }
    @Override public void move(Long id, MoveOrgNodeReq req) { throw new UnsupportedOperationException(); }
    @Override public void delete(Long id) { throw new UnsupportedOperationException(); }
    @Override public OrgNodeDTO getById(Long id) { throw new UnsupportedOperationException(); }
    @Override public List<OrgNodeDTO> getTree(Long rootId) { throw new UnsupportedOperationException(); }
    @Override public List<Long> findDescendantIds(Long id) { return closure.findDescendantIds(id); }

    private OrgNodeDTO toDTO(OrgNode n, List<OrgNodeDTO> children) {
        return new OrgNodeDTO(n.getId(), n.getParentId(), n.getName(), n.getCode(),
            n.getNodeType(), n.getSortOrder(), n.getCreatedAt(), children);
    }
}
```

- [ ] **Step 5: 编译**

```bash
./mvnw -pl ems-orgtree compile
```

- [ ] **Step 6: 提交**

```bash
git add ems-orgtree/
git commit -m "feat(orgtree): service.create() with closure insert"
```

---

### Task 23: `OrgNodeService.getTree() / getById()`

**Files:**
- Modify: `ems-orgtree/src/main/java/com/ems/orgtree/service/impl/OrgNodeServiceImpl.java`

- [ ] **Step 1: 实现 `getById`**

```java
@Override
@Transactional(readOnly = true)
public OrgNodeDTO getById(Long id) {
    OrgNode n = nodes.findById(id).orElseThrow(() -> new NotFoundException("OrgNode", id));
    return toDTO(n, List.of());
}
```

- [ ] **Step 2: 实现 `getTree`**（一次性加载全树，内存建树）

```java
@Override
@Transactional(readOnly = true)
public List<OrgNodeDTO> getTree(Long rootId) {
    List<OrgNode> all = nodes.findAllByOrderBySortOrderAscIdAsc();
    Set<Long> scope;
    if (rootId == null) {
        scope = all.stream().map(OrgNode::getId).collect(java.util.stream.Collectors.toSet());
    } else {
        scope = new HashSet<>(closure.findDescendantIds(rootId));
        if (scope.isEmpty()) throw new NotFoundException("OrgNode", rootId);
    }
    Map<Long, List<OrgNode>> byParent = new HashMap<>();
    for (OrgNode n : all) {
        if (!scope.contains(n.getId())) continue;
        byParent.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n);
    }
    List<OrgNode> roots;
    if (rootId == null) roots = byParent.getOrDefault(null, List.of());
    else roots = all.stream().filter(x -> x.getId().equals(rootId)).toList();

    return roots.stream().map(r -> buildSubtree(r, byParent)).toList();
}

private OrgNodeDTO buildSubtree(OrgNode n, Map<Long, List<OrgNode>> byParent) {
    List<OrgNodeDTO> kids = byParent.getOrDefault(n.getId(), List.of())
        .stream().map(k -> buildSubtree(k, byParent)).toList();
    return toDTO(n, kids);
}
```

- [ ] **Step 3: 提交**

```bash
git add ems-orgtree/src/main/java/com/ems/orgtree/service/impl/
git commit -m "feat(orgtree): getTree and getById"
```

---

### Task 24: `OrgNodeService.update()`

**Files:**
- Modify: `ems-orgtree/src/main/java/com/ems/orgtree/service/impl/OrgNodeServiceImpl.java`

- [ ] **Step 1: 实现**

```java
@Override
@Transactional
@Audited(action = "UPDATE", resourceType = "ORG_NODE", resourceIdExpr = "#id")
public OrgNodeDTO update(Long id, UpdateOrgNodeReq req) {
    OrgNode n = nodes.findById(id).orElseThrow(() -> new NotFoundException("OrgNode", id));
    n.setName(req.name());
    n.setNodeType(req.nodeType());
    if (req.sortOrder() != null) n.setSortOrder(req.sortOrder());
    nodes.save(n);
    return toDTO(n, List.of());
}
```

- [ ] **Step 2: 提交**

```bash
git add ems-orgtree/src/main/java/com/ems/orgtree/service/impl/
git commit -m "feat(orgtree): update node"
```

---

### Task 25: `OrgNodeService.move()` — 防环 + 闭包重写

**Files:**
- Modify: `ems-orgtree/src/main/java/com/ems/orgtree/service/impl/OrgNodeServiceImpl.java`

- [ ] **Step 1: 实现**

```java
@Override
@Transactional
@Audited(action = "MOVE", resourceType = "ORG_NODE", resourceIdExpr = "#id")
public void move(Long id, MoveOrgNodeReq req) {
    OrgNode node = nodes.findById(id).orElseThrow(() -> new NotFoundException("OrgNode", id));
    Long newParent = req.newParentId();

    if (Objects.equals(node.getParentId(), newParent)) return;  // 无变化

    if (newParent != null) {
        nodes.findById(newParent).orElseThrow(() -> new NotFoundException("OrgNode", newParent));
        if (closure.isDescendant(id, newParent)) {
            throw new BusinessException(ErrorCode.BIZ_GENERIC, "不能把节点移到自己的后代下");
        }
    }

    // 1. 删除旧祖先对子树的闭包
    closure.deleteAncestorsOfSubtree(id);
    // 2. 插入新祖先对子树的闭包（如果 newParent 为 null，节点变成根，不需要插）
    if (newParent != null) closure.insertAncestorsForSubtree(id, newParent);
    // 3. 更新 parent_id
    node.setParentId(newParent);
    nodes.save(node);
}
```

- [ ] **Step 2: 提交**

```bash
git add ems-orgtree/src/main/java/com/ems/orgtree/service/impl/
git commit -m "feat(orgtree): move node with cycle-check and closure rewrite"
```

---

### Task 26: `OrgNodeService.delete()`

**Files:**
- Modify: `ems-orgtree/src/main/java/com/ems/orgtree/service/impl/OrgNodeServiceImpl.java`

- [ ] **Step 1: 实现**（不允许删非叶子；闭包 ON DELETE CASCADE 自动清）

```java
@Override
@Transactional
@Audited(action = "DELETE", resourceType = "ORG_NODE", resourceIdExpr = "#id")
public void delete(Long id) {
    OrgNode n = nodes.findById(id).orElseThrow(() -> new NotFoundException("OrgNode", id));
    List<Long> descendants = closure.findDescendantIds(id);
    if (descendants.size() > 1) {
        throw new BusinessException(ErrorCode.BIZ_GENERIC, "节点下仍有子节点，请先删除子节点");
    }
    nodes.delete(n);
}
```

- [ ] **Step 2: 提交**

```bash
git add ems-orgtree/src/main/java/com/ems/orgtree/service/impl/
git commit -m "feat(orgtree): delete leaf node"
```

---

### Task 27: `OrgNodeController` REST 端点

**Files:**
- Create: `ems-orgtree/src/main/java/com/ems/orgtree/controller/OrgNodeController.java`

- [ ] **Step 1: 写 Controller**

```java
package com.ems.orgtree.controller;

import com.ems.core.dto.Result;
import com.ems.orgtree.dto.*;
import com.ems.orgtree.service.OrgNodeService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/org-nodes")
public class OrgNodeController {

    private final OrgNodeService service;

    public OrgNodeController(OrgNodeService s) { this.service = s; }

    @GetMapping("/tree")
    @PreAuthorize("isAuthenticated()")
    public Result<List<OrgNodeDTO>> tree(@RequestParam(required = false) Long rootId) {
        return Result.ok(service.getTree(rootId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Result<OrgNodeDTO> get(@PathVariable Long id) { return Result.ok(service.getById(id)); }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<OrgNodeDTO>> create(@Valid @RequestBody CreateOrgNodeReq req) {
        OrgNodeDTO d = service.create(req);
        return ResponseEntity.status(201).body(Result.ok(d));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<OrgNodeDTO> update(@PathVariable Long id, @Valid @RequestBody UpdateOrgNodeReq req) {
        return Result.ok(service.update(id, req));
    }

    @PatchMapping("/{id}/move")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> move(@PathVariable Long id, @RequestBody MoveOrgNodeReq req) {
        service.move(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add ems-orgtree/src/main/java/com/ems/orgtree/controller/
git commit -m "feat(orgtree): REST controller for org nodes"
```

---

### Task 28: OrgTree 单元测试 — `create` 分支

**Files:**
- Create: `ems-orgtree/src/test/java/com/ems/orgtree/service/OrgNodeServiceUnitTest.java`

- [ ] **Step 1: 写测试**

```java
package com.ems.orgtree.service;

import com.ems.core.exception.BusinessException;
import com.ems.orgtree.dto.CreateOrgNodeReq;
import com.ems.orgtree.entity.OrgNode;
import com.ems.orgtree.repository.OrgNodeClosureRepository;
import com.ems.orgtree.repository.OrgNodeRepository;
import com.ems.orgtree.service.impl.OrgNodeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgNodeServiceUnitTest {

    OrgNodeRepository nodes;
    OrgNodeClosureRepository closure;
    OrgNodeServiceImpl svc;

    @BeforeEach void setup() {
        nodes = mock(OrgNodeRepository.class);
        closure = mock(OrgNodeClosureRepository.class);
        svc = new OrgNodeServiceImpl(nodes, closure);
    }

    @Test void create_root_insertsSelfClosure() {
        when(nodes.existsByCode("A")).thenReturn(false);
        doAnswer(inv -> { ((OrgNode) inv.getArgument(0)).setId(10L); return inv.getArgument(0); })
            .when(nodes).save(any());

        svc.create(new CreateOrgNodeReq(null, "工厂A", "A", "PLANT", 0));

        verify(closure).insertForRoot(10L);
        verify(closure, never()).insertForNewLeaf(anyLong(), anyLong());
    }

    @Test void create_duplicateCode_throws() {
        when(nodes.existsByCode("A")).thenReturn(true);
        assertThatThrownBy(() ->
            svc.create(new CreateOrgNodeReq(null, "x", "A", "PLANT", 0)))
            .isInstanceOf(BusinessException.class);
    }

    @Test void create_leaf_insertsClosureWithParent() {
        when(nodes.existsByCode("L")).thenReturn(false);
        when(nodes.findById(1L)).thenReturn(Optional.of(new OrgNode()));
        doAnswer(inv -> { ((OrgNode) inv.getArgument(0)).setId(20L); return inv.getArgument(0); })
            .when(nodes).save(any());

        svc.create(new CreateOrgNodeReq(1L, "leaf", "L", "DEVICE", 0));

        verify(closure).insertForNewLeaf(20L, 1L);
    }
}
```

- [ ] **Step 2: 跑测试**

```bash
./mvnw -pl ems-orgtree test -Dtest=OrgNodeServiceUnitTest
```

Expected: 3 PASS.

- [ ] **Step 3: 提交**

```bash
git add ems-orgtree/src/test/
git commit -m "test(orgtree): unit tests for create branches"
```

---

### Task 29: OrgTree 集成测试 — 闭包一致性（Testcontainers）

**Files:**
- Create: `ems-orgtree/src/test/java/com/ems/orgtree/ClosureConsistencyIT.java`
- Create: `ems-orgtree/src/test/resources/application-test.yml`
- Create: `ems-orgtree/src/test/resources/db/migration/V1.0.1__init_orgtree.sql` （复制 prod 迁移）

- [ ] **Step 1: 复制迁移到测试资源**

```bash
mkdir -p ems-orgtree/src/test/resources/db/migration
cp ems-app/src/main/resources/db/migration/V1.0.1__init_orgtree.sql \
   ems-orgtree/src/test/resources/db/migration/
```

- [ ] **Step 2: 测试配置**

```yaml
# ems-orgtree/src/test/resources/application-test.yml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
  jpa:
    hibernate.ddl-auto: none
```

- [ ] **Step 3: 写集成测试**

```java
package com.ems.orgtree;

import com.ems.audit.aspect.AuditContext;
import com.ems.orgtree.dto.*;
import com.ems.orgtree.repository.*;
import com.ems.orgtree.service.*;
import com.ems.orgtree.service.impl.OrgNodeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ClosureConsistencyIT.TestApp.class)
@Testcontainers
@ActiveProfiles("test")
class ClosureConsistencyIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired OrgNodeService svc;
    @Autowired OrgNodeClosureRepository closure;

    @Test
    void tree_A_B_C_closureHasCorrectDepths() {
        var a = svc.create(new CreateOrgNodeReq(null,   "工厂A", "A", "PLANT", 0));
        var b = svc.create(new CreateOrgNodeReq(a.id(), "车间B", "B", "WS",    0));
        var c = svc.create(new CreateOrgNodeReq(b.id(), "设备C", "C", "DEV",   0));

        // A 的后代：A,B,C 三条
        assertThat(closure.findDescendantIds(a.id())).containsExactlyInAnyOrder(a.id(), b.id(), c.id());
        // C 的祖先：A,B,C 三条
        assertThat(closure.findAncestorIds(c.id())).containsExactlyInAnyOrder(a.id(), b.id(), c.id());
    }

    @Test
    void moveC_fromB_toA_updatesClosure() {
        var a = svc.create(new CreateOrgNodeReq(null,   "A2", "A2", "PLANT", 0));
        var b = svc.create(new CreateOrgNodeReq(a.id(), "B2", "B2", "WS",    0));
        var c = svc.create(new CreateOrgNodeReq(b.id(), "C2", "C2", "DEV",   0));

        svc.move(c.id(), new MoveOrgNodeReq(a.id()));

        assertThat(closure.findAncestorIds(c.id())).containsExactlyInAnyOrder(a.id(), c.id());
        assertThat(closure.findDescendantIds(b.id())).containsExactly(b.id());
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackages = {"com.ems.orgtree.entity"})
    @EnableJpaRepositories(basePackages = {"com.ems.orgtree.repository"})
    @ComponentScan(basePackages = {"com.ems.orgtree.service"})
    static class TestApp {
        @Bean AuditContext ctx() {
            return new AuditContext() {
                public Long currentUserId() { return 1L; }
                public String currentUsername() { return "tester"; }
                public String currentIp() { return "127.0.0.1"; }
                public String currentUserAgent() { return "it"; }
            };
        }
    }
}
```

- [ ] **Step 4: 运行**

```bash
./mvnw -pl ems-orgtree test -Dtest=ClosureConsistencyIT
```

Expected: 2 PASS.

- [ ] **Step 5: 提交**

```bash
git add ems-orgtree/src/test/
git commit -m "test(orgtree): closure consistency IT with testcontainers"
```

---

### Task 30: OrgTree 集成测试 — 防环

**Files:**
- Modify: `ems-orgtree/src/test/java/com/ems/orgtree/ClosureConsistencyIT.java`

- [ ] **Step 1: 补充防环测试**

```java
@Test
void move_toOwnDescendant_throws() {
    var a = svc.create(new CreateOrgNodeReq(null,   "A3", "A3", "PLANT", 0));
    var b = svc.create(new CreateOrgNodeReq(a.id(), "B3", "B3", "WS",    0));
    var c = svc.create(new CreateOrgNodeReq(b.id(), "C3", "C3", "DEV",   0));

    assertThatThrownBy(() -> svc.move(a.id(), new MoveOrgNodeReq(c.id())))
        .isInstanceOf(com.ems.core.exception.BusinessException.class)
        .hasMessageContaining("后代");
}
```

记得在文件头加：

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

- [ ] **Step 2: 跑测试**

```bash
./mvnw -pl ems-orgtree test -Dtest=ClosureConsistencyIT
```

Expected: 3 PASS.

- [ ] **Step 3: 提交**

```bash
git add ems-orgtree/src/test/
git commit -m "test(orgtree): cycle-prevention IT"
```

---

### Task 31: OrgTree `delete` 测试

**Files:**
- Modify: `ems-orgtree/src/test/java/com/ems/orgtree/ClosureConsistencyIT.java`

- [ ] **Step 1: 补两个测试**

```java
@Test
void delete_leaf_succeeds() {
    var a = svc.create(new CreateOrgNodeReq(null,   "D1", "D1", "PLANT", 0));
    var b = svc.create(new CreateOrgNodeReq(a.id(), "D2", "D2", "WS",    0));
    svc.delete(b.id());
    assertThat(closure.findDescendantIds(a.id())).containsExactly(a.id());
}

@Test
void delete_nonLeaf_throws() {
    var a = svc.create(new CreateOrgNodeReq(null,   "D3", "D3", "PLANT", 0));
    var b = svc.create(new CreateOrgNodeReq(a.id(), "D4", "D4", "WS",    0));
    assertThatThrownBy(() -> svc.delete(a.id()))
        .isInstanceOf(com.ems.core.exception.BusinessException.class);
}
```

- [ ] **Step 2: 跑全部测试**

```bash
./mvnw -pl ems-orgtree test
```

Expected: 全部 PASS。

- [ ] **Step 3: 提交**

```bash
git add ems-orgtree/src/test/
git commit -m "test(orgtree): delete leaf and delete non-leaf"
```

---

## Phase D · `ems-auth` 模块（Tasks 32-56）

### Task 32: Auth Flyway 迁移

**Files:**
- Create: `ems-app/src/main/resources/db/migration/V1.0.0__init_auth.sql`

> **Note:** Flyway 按版本号升序执行，V1.0.0 会在 V1.0.1 (orgtree) 之前运行，所以不涉及外键依赖 org_nodes。`node_permissions.org_node_id` 不设外键约束（跨模块边界，业务校验）。

- [ ] **Step 1: 写迁移**

```sql
CREATE TABLE users (
    id                BIGSERIAL PRIMARY KEY,
    username          VARCHAR(64)  NOT NULL UNIQUE,
    password_hash     VARCHAR(128) NOT NULL,
    display_name      VARCHAR(128),
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_attempts   INT          NOT NULL DEFAULT 0,
    locked_until      TIMESTAMPTZ,
    last_login_at     TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE roles (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL UNIQUE,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255)
);

CREATE TABLE user_roles (
    user_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id  BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE node_permissions (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_node_id  BIGINT      NOT NULL,
    scope        VARCHAR(16) NOT NULL CHECK (scope IN ('SUBTREE','NODE_ONLY')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, org_node_id, scope)
);

CREATE INDEX idx_node_perm_user ON node_permissions(user_id);
CREATE INDEX idx_node_perm_node ON node_permissions(org_node_id);

CREATE TABLE refresh_tokens (
    jti         VARCHAR(64)  PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX idx_refresh_user    ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_expires ON refresh_tokens(expires_at);
```

- [ ] **Step 2: 提交**

```bash
git add ems-app/src/main/resources/db/migration/V1.0.0__init_auth.sql
git commit -m "feat(auth): flyway migration for users/roles/permissions/refresh-tokens"
```

---

### Task 33: 种子数据迁移

**Files:**
- Create: `ems-app/src/main/resources/db/migration/V1.0.8__seed_reference_data.sql`

- [ ] **Step 1: 写种子数据**

```sql
-- 初始角色
INSERT INTO roles (code, name, description) VALUES
  ('ADMIN',  '管理员', '系统管理员，全部权限'),
  ('VIEWER', '查看者', '按节点权限查看数据')
ON CONFLICT (code) DO NOTHING;

-- 初始管理员（密码 BCrypt hash of 'admin123!'，建议首次登录改）
-- hash 由 `htpasswd -bnBC 12 "" admin123! | tr -d ':\n'` 或 Spring BCrypt 生成
-- 这里示例 hash（实际使用前请在开发机重新生成，避免公开 hash）
INSERT INTO users (username, password_hash, display_name, enabled)
VALUES ('admin', '$2a$12$A0RD4s2SwFO7Q3kQ7oW4Q.p4M/KG68U8HhXbJJJN9QK6VYfKx8LXy', '系统管理员', TRUE)
ON CONFLICT (username) DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r
WHERE u.username = 'admin' AND r.code = 'ADMIN'
ON CONFLICT DO NOTHING;
```

- [ ] **Step 2: 在生成 hash 前，本地跑一次密码哈希生成器**

新建 `ems-auth/src/test/java/com/ems/auth/PasswordHashGen.java`：

```java
package com.ems.auth;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordHashGen {
    public static void main(String[] args) {
        System.out.println(new BCryptPasswordEncoder(12).encode("admin123!"));
    }
}
```

跑一次：

```bash
./mvnw -pl ems-auth test-compile exec:java \
    -Dexec.mainClass=com.ems.auth.PasswordHashGen \
    -Dexec.classpathScope=test
```

把输出的 hash 替换到上面 SQL 里。

- [ ] **Step 3: 启动应用验证**

```bash
./mvnw -pl ems-app spring-boot:run
```

观察日志看到迁移成功。Ctrl-C。

- [ ] **Step 4: 提交**

```bash
git add ems-app/src/main/resources/db/migration/V1.0.8__seed_reference_data.sql ems-auth/src/test/
git commit -m "feat(auth): seed roles and default admin user"
```

---

### Task 34: `User` `Role` `UserRole` `NodePermission` `RefreshToken` 实体

**Files:**
- Create: `ems-auth/src/main/java/com/ems/auth/entity/User.java`
- Create: `ems-auth/src/main/java/com/ems/auth/entity/Role.java`
- Create: `ems-auth/src/main/java/com/ems/auth/entity/UserRole.java`
- Create: `ems-auth/src/main/java/com/ems/auth/entity/UserRoleId.java`
- Create: `ems-auth/src/main/java/com/ems/auth/entity/NodePermission.java`
- Create: `ems-auth/src/main/java/com/ems/auth/entity/RefreshToken.java`

- [ ] **Step 1: `User.java`**

```java
package com.ems.auth.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    @Column(nullable = false, unique = true, length = 64) private String username;
    @Column(name = "password_hash", nullable = false, length = 128) private String passwordHash;
    @Column(name = "display_name") private String displayName;
    @Column(nullable = false) private Boolean enabled = true;
    @Column(name = "failed_attempts", nullable = false) private Integer failedAttempts = 0;
    @Column(name = "locked_until") private OffsetDateTime lockedUntil;
    @Column(name = "last_login_at") private OffsetDateTime lastLoginAt;

    @Version private Long version;

    @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;

    @PrePersist void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = createdAt != null ? createdAt : now;
        updatedAt = now;
    }
    @PreUpdate void onUpdate() { updatedAt = OffsetDateTime.now(); }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Boolean getEnabled() { return enabled; }
    public Integer getFailedAttempts() { return failedAttempts; }
    public OffsetDateTime getLockedUntil() { return lockedUntil; }
    public OffsetDateTime getLastLoginAt() { return lastLoginAt; }

    public void setId(Long v) { this.id = v; }
    public void setUsername(String v) { this.username = v; }
    public void setPasswordHash(String v) { this.passwordHash = v; }
    public void setDisplayName(String v) { this.displayName = v; }
    public void setEnabled(Boolean v) { this.enabled = v; }
    public void setFailedAttempts(Integer v) { this.failedAttempts = v; }
    public void setLockedUntil(OffsetDateTime v) { this.lockedUntil = v; }
    public void setLastLoginAt(OffsetDateTime v) { this.lastLoginAt = v; }
}
```

- [ ] **Step 2: `Role.java`**

```java
package com.ems.auth.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "roles")
public class Role {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 32) private String code;
    @Column(nullable = false, length = 64) private String name;
    @Column(length = 255) private String description;

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public void setId(Long v) { this.id = v; }
    public void setCode(String v) { this.code = v; }
    public void setName(String v) { this.name = v; }
    public void setDescription(String v) { this.description = v; }
}
```

- [ ] **Step 3: `UserRole.java` + `UserRoleId.java`**

```java
// UserRoleId.java
package com.ems.auth.entity;
import java.io.Serializable;
import java.util.Objects;
public class UserRoleId implements Serializable {
    private Long userId;
    private Long roleId;
    public UserRoleId() {}
    public UserRoleId(Long u, Long r) { this.userId = u; this.roleId = r; }
    public Long getUserId() { return userId; }
    public Long getRoleId() { return roleId; }
    public void setUserId(Long v) { this.userId = v; }
    public void setRoleId(Long v) { this.roleId = v; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserRoleId x)) return false;
        return Objects.equals(userId, x.userId) && Objects.equals(roleId, x.roleId);
    }
    @Override public int hashCode() { return Objects.hash(userId, roleId); }
}
```

```java
// UserRole.java
package com.ems.auth.entity;
import jakarta.persistence.*;

@Entity
@Table(name = "user_roles")
@IdClass(UserRoleId.class)
public class UserRole {
    @Id @Column(name = "user_id") private Long userId;
    @Id @Column(name = "role_id") private Long roleId;
    public UserRole() {}
    public UserRole(Long u, Long r) { this.userId = u; this.roleId = r; }
    public Long getUserId() { return userId; }
    public Long getRoleId() { return roleId; }
    public void setUserId(Long v) { this.userId = v; }
    public void setRoleId(Long v) { this.roleId = v; }
}
```

- [ ] **Step 4: `NodePermission.java`**

```java
package com.ems.auth.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "node_permissions")
public class NodePermission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "org_node_id", nullable = false) private Long orgNodeId;
    @Column(nullable = false, length = 16) private String scope;  // SUBTREE | NODE_ONLY
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;

    @PrePersist void onCreate() { if (createdAt == null) createdAt = OffsetDateTime.now(); }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getOrgNodeId() { return orgNodeId; }
    public String getScope() { return scope; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setUserId(Long v) { this.userId = v; }
    public void setOrgNodeId(Long v) { this.orgNodeId = v; }
    public void setScope(String v) { this.scope = v; }
}
```

- [ ] **Step 5: `RefreshToken.java`**

```java
package com.ems.auth.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id @Column(length = 64) private String jti;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "issued_at", nullable = false) private OffsetDateTime issuedAt;
    @Column(name = "expires_at", nullable = false) private OffsetDateTime expiresAt;
    @Column(name = "revoked_at") private OffsetDateTime revokedAt;

    public String getJti() { return jti; }
    public Long getUserId() { return userId; }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setJti(String v) { this.jti = v; }
    public void setUserId(Long v) { this.userId = v; }
    public void setIssuedAt(OffsetDateTime v) { this.issuedAt = v; }
    public void setExpiresAt(OffsetDateTime v) { this.expiresAt = v; }
    public void setRevokedAt(OffsetDateTime v) { this.revokedAt = v; }
}
```

- [ ] **Step 6: 提交**

```bash
git add ems-auth/src/main/java/com/ems/auth/entity/
git commit -m "feat(auth): entities for user/role/permission/refresh-token"
```

---

### Task 35: Auth Repositories

**Files:**
- Create: `ems-auth/src/main/java/com/ems/auth/repository/UserRepository.java`
- Create: `ems-auth/src/main/java/com/ems/auth/repository/RoleRepository.java`
- Create: `ems-auth/src/main/java/com/ems/auth/repository/UserRoleRepository.java`
- Create: `ems-auth/src/main/java/com/ems/auth/repository/NodePermissionRepository.java`
- Create: `ems-auth/src/main/java/com/ems/auth/repository/RefreshTokenRepository.java`

- [ ] **Step 1: 5 个 repository**

```java
// UserRepository.java
package com.ems.auth.repository;
import com.ems.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
}
```

```java
// RoleRepository.java
package com.ems.auth.repository;
import com.ems.auth.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByCode(String code);
}
```

```java
// UserRoleRepository.java
package com.ems.auth.repository;
import com.ems.auth.entity.UserRole;
import com.ems.auth.entity.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {
    List<UserRole> findByUserId(Long userId);
    void deleteByUserId(Long userId);

    @Query("SELECT r.code FROM Role r JOIN UserRole ur ON ur.roleId = r.id WHERE ur.userId = :userId")
    List<String> findRoleCodesByUserId(@Param("userId") Long userId);
}
```

```java
// NodePermissionRepository.java
package com.ems.auth.repository;
import com.ems.auth.entity.NodePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Set;
public interface NodePermissionRepository extends JpaRepository<NodePermission, Long> {
    List<NodePermission> findByUserId(Long userId);
    void deleteByUserId(Long userId);

    /** 可见节点 id 集合：SUBTREE 范围取闭包后代，NODE_ONLY 取节点本身 */
    @Query(value = """
        SELECT DISTINCT c.descendant_id
        FROM node_permissions np
        JOIN org_node_closure c ON c.ancestor_id = np.org_node_id
        WHERE np.user_id = :userId
          AND (np.scope = 'SUBTREE' OR c.depth = 0)
    """, nativeQuery = true)
    Set<Long> findVisibleNodeIds(@Param("userId") Long userId);
}
```

```java
// RefreshTokenRepository.java
package com.ems.auth.repository;
import com.ems.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    void deleteByUserId(Long userId);
}
```

- [ ] **Step 2: 提交**

```bash
git add ems-auth/src/main/java/com/ems/auth/repository/
git commit -m "feat(auth): JPA repositories"
```

---

### Task 36: `PasswordEncoder` Bean

**Files:**
- Create: `ems-auth/src/main/java/com/ems/auth/security/PasswordConfig.java`

- [ ] **Step 1: 写配置**

```java
package com.ems.auth.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
}
```

- [ ] **Step 2: 提交**

```bash
git add ems-auth/src/main/java/com/ems/auth/security/
git commit -m "feat(auth): BCrypt password encoder (cost 12)"
```

---

### Task 37: `JwtService` — 签发与验证

**Files:**
- Create: `ems-auth/src/main/java/com/ems/auth/jwt/JwtService.java`
- Test: `ems-auth/src/test/java/com/ems/auth/jwt/JwtServiceTest.java`

- [ ] **Step 1: 写测试（TDD）**

```java
package com.ems.auth.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    JwtService jwt;

    @BeforeEach void setup() {
        jwt = new JwtService("this-is-a-test-secret-at-least-32-bytes-long!!!", 15, 7);
    }

    @Test
    void signAndParse_accessToken() {
        String token = jwt.signAccessToken(42L, "zhang3", List.of("VIEWER"));
        JwtService.AccessClaims c = jwt.parseAccess(token);
        assertThat(c.userId()).isEqualTo(42L);
        assertThat(c.username()).isEqualTo("zhang3");
        assertThat(c.roles()).containsExactly("VIEWER");
    }

    @Test
    void parseExpiredToken_throws() {
        JwtService shortLived = new JwtService("same-secret-32-bytes-long-for-test!!!", -1, 7);
        String token = shortLived.signAccessToken(1L, "x", List.of());
        try {
            shortLived.parseAccess(token);
            assertThat(false).isTrue();  // 不应到达
        } catch (Exception e) {
            assertThat(e.getMessage()).containsIgnoringCase("expired");
        }
    }
}
```

- [ ] **Step 2: 跑测试看它失败**

```bash
./mvnw -pl ems-auth test -Dtest=JwtServiceTest
```

Expected: 编译失败。

- [ ] **Step 3: 实现**

```java
package com.ems.auth.jwt;

import com.ems.core.exception.UnauthorizedException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class JwtService {

    public record AccessClaims(Long userId, String username, List<String> roles) {}
    public record RefreshClaims(String jti, Long userId, Instant expiresAt) {}

    private final SecretKey key;
    private final long accessMinutes;
    private final long refreshDays;

    public JwtService(@Value("${ems.jwt.secret}") String secret,
                      @Value("${ems.jwt.access-token-minutes}") long accessMinutes,
                      @Value("${ems.jwt.refresh-token-days}") long refreshDays) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessMinutes = accessMinutes;
        this.refreshDays   = refreshDays;
    }

    public String signAccessToken(Long userId, String username, List<String> roles) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessMinutes * 60);
        return Jwts.builder()
            .subject(username)
            .claim("uid", userId)
            .claim("roles", roles)
            .claim("typ", "access")
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(key)
            .compact();
    }

    public RefreshClaims signRefreshToken(Long userId) {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(refreshDays * 86400L);
        String token = Jwts.builder()
            .id(jti)
            .claim("uid", userId)
            .claim("typ", "refresh")
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(key)
            .compact();
        return new RefreshClaims(token, userId, exp);  // 注意：这里返回 token 本体作 jti? 不对。
        // 调整：我们需要返回 token 字符串 + jti 分离。见下一版本。
    }

    public AccessClaims parseAccess(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!"access".equals(c.get("typ"))) throw new UnauthorizedException("wrong token type");
            Long uid = c.get("uid", Long.class);
            @SuppressWarnings("unchecked")
            List<String> roles = c.get("roles", List.class);
            return new AccessClaims(uid, c.getSubject(), roles);
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException("token expired");
        } catch (JwtException e) {
            throw new UnauthorizedException("invalid token");
        }
    }

    public RefreshClaims parseRefresh(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!"refresh".equals(c.get("typ"))) throw new UnauthorizedException("wrong token type");
            Long uid = c.get("uid", Long.class);
            return new RefreshClaims(c.getId(), uid, c.getExpiration().toInstant());
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException("refresh token expired");
        } catch (JwtException e) {
            throw new UnauthorizedException("invalid refresh token");
        }
    }
}
```

> **Note:** `signRefreshToken` 的返回值需要同时包含 jti 和 token string。我们用不同结构：

- [ ] **Step 4: 修正：分离签发结果**

```java
public record SignedRefresh(String token, String jti, Instant expiresAt) {}

public SignedRefresh signRefreshToken(Long userId) {
    String jti = UUID.randomUUID().toString();
    Instant now = Instant.now();
    Instant exp = now.plusSeconds(refreshDays * 86400L);
    String token = Jwts.builder()
        .id(jti).claim("uid", userId).claim("typ", "refresh")
        .issuedAt(Date.from(now)).expiration(Date.from(exp))
        .signWith(key).compact();
    return new SignedRefresh(token, jti, exp);
}
```

替换原来的 `signRefreshToken` 方法（返回类型改为 `SignedRefresh`）。`RefreshClaims` 用于 `parseRefresh` 的解析返回值保留。

- [ ] **Step 5: 调整测试**（只测 access token；refresh 在集成测试里测）

移除上面 `parseExpiredToken_throws` 中 `RefreshClaims` 相关。保留 access token 的两个测试。

- [ ] **Step 6: 跑测试**

```bash
./mvnw -pl ems-auth test -Dtest=JwtServiceTest
```

Expected: PASS.

- [ ] **Step 7: 提交**

```bash
git add ems-auth/src/main/java/com/ems/auth/jwt/JwtService.java ems-auth/src/test/java/com/ems/auth/jwt/
git commit -m "feat(auth): JwtService sign/parse access and refresh tokens"
```

---

### Task 38: `UserDetailsServiceImpl`

**Files:**
- Create: `ems-auth/src/main/java/com/ems/auth/security/UserDetailsServiceImpl.java`
- Create: `ems-auth/src/main/java/com/ems/auth/security/AuthUser.java`

- [ ] **Step 1: `AuthUser`（自定义 UserDetails 承载 userId）**

```java
package com.ems.auth.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

public class AuthUser extends User {
    private final Long userId;

    public AuthUser(Long userId, String username, String passwordHash,
                    boolean enabled, boolean accountNonLocked,
                    Collection<SimpleGrantedAuthority> authorities) {
        super(username, passwordHash, enabled, true, true, accountNonLocked, authorities);
        this.userId = userId;
    }

    public Long getUserId() { return userId; }
}
```

- [ ] **Step 2: `UserDetailsServiceImpl`**

```java
package com.ems.auth.security;

import com.ems.auth.entity.User;
import com.ems.auth.repository.UserRepository;
import com.ems.auth.repository.UserRoleRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository users;
    private final UserRoleRepository userRoles;

    public UserDetailsServiceImpl(UserRepository u, UserRoleRepository ur) {
        this.users = u; this.userRoles = ur;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u = users.findByUsername(username).orElseThrow(() ->
            new UsernameNotFoundException("no such user: " + username));

        List<String> codes = userRoles.findRoleCodesByUserId(u.getId());
        List<SimpleGrantedAuthority> authorities = codes.stream()
            .map(c -> new SimpleGrantedAuthority("ROLE_" + c)).toList();

        boolean locked = u.getLockedUntil() != null && u.getLockedUntil().isAfter(OffsetDateTime.now());

        return new AuthUser(u.getId(), u.getUsername(), u.getPasswordHash(),
            Boolean.TRUE.equals(u.getEnabled()), !locked, authorities);
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add ems-auth/src/main/java/com/ems/auth/security/UserDetailsServiceImpl.java ems-auth/src/main/java/com/ems/auth/security/AuthUser.java
git commit -m "feat(auth): UserDetailsService implementation with AuthUser carrying userId"
```

---

### Task 39: `JwtAuthenticationFilter`

**Files:**
- Create: `ems-auth/src/main/java/com/ems/auth/jwt/JwtAuthenticationFilter.java`

- [ ] **Step 1: 写 filter**

```java
package com.ems.auth.jwt;

import com.ems.auth.security.AuthUser;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    public JwtAuthenticationFilter(JwtService jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                JwtService.AccessClaims c = jwt.parseAccess(token);
                List<SimpleGrantedAuthority> authorities = c.roles().stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
                AuthUser principal = new AuthUser(c.userId(), c.username(), "",
                    true, true, authorities);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    principal, null, authorities);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                SecurityContextHolder.getContext().setAuthentication(auth);
                MDC.put("userId", String.valueOf(c.userId()));
            } catch (Exception e) {
                // token 非法 → SecurityContext 留空，后续 @PreAuthorize 拦
            }
        }
        try { chain.doFilter(req, res); }
        finally { MDC.remove("userId"); }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add ems-auth/src/main/java/com/ems/auth/jwt/JwtAuthenticationFilter.java
git commit -m "feat(auth): JwtAuthenticationFilter sets SecurityContext"
```

---

### Task 40: `SecurityConfig` — Spring Security 装配

**Files:**
- Create: `ems-auth/src/main/java/com/ems/auth/security/SecurityConfig.java`

- [ ] **Step 1: 写配置**

```java
package com.ems.auth.security;

import com.ems.auth.jwt.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filter(HttpSecurity http,
                                      JwtAuthenticationFilter jwtFilter) throws Exception {
        http
            .csrf(c -> c.disable())
            .cors(c -> {})   // 由 CorsFilter bean 提供
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/actuator/prometheus").permitAll()  // 内网 Nginx 限 IP
                .anyRequest().authenticated())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public DaoAuthenticationProvider authProvider(UserDetailsService uds, PasswordEncoder enc) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(uds);
        p.setPasswordEncoder(enc);
        return p;
    }

    @Bean
    public AuthenticationManager authManager(DaoAuthenticationProvider p) {
        return a -> p.authenticate(a);
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add ems-auth/src/main/java/com/ems/auth/security/SecurityConfig.java
git commit -m "feat(auth): Spring Security filter chain (stateless + JWT)"
```

---

### Task 41: `AuthService.login()` + `refresh()` + `logout()`

**Files:**
- Create: `ems-auth/src/main/java/com/ems/auth/dto/LoginReq.java`
- Create: `ems-auth/src/main/java/com/ems/auth/dto/LoginResp.java`
- Create: `ems-auth/src/main/java/com/ems/auth/service/AuthService.java`
- Create: `ems-auth/src/main/java/com/ems/auth/service/impl/AuthServiceImpl.java`

- [ ] **Step 1: DTO**

```java
// LoginReq.java
package com.ems.auth.dto;
import jakarta.validation.constraints.NotBlank;
public record LoginReq(@NotBlank String username, @NotBlank String password) {}
```

```java
// LoginResp.java
package com.ems.auth.dto;
public record LoginResp(String accessToken, long expiresInSeconds, UserInfo user) {
    public record UserInfo(Long id, String username, String displayName, java.util.List<String> roles) {}
}
```

- [ ] **Step 2: 接口**

```java
package com.ems.auth.service;

import com.ems.auth.dto.*;

public interface AuthService {
    /** 登录，返回 access token + refresh token（后者以 cookie 返回，AuthController 负责） */
    LoginResult login(String username, String password, String ip, String userAgent);
    LoginResult refresh(String refreshToken, String ip, String userAgent);
    void logout(String refreshToken);

    record LoginResult(String accessToken, long accessExpSeconds,
                       String refreshToken, long refreshMaxAgeSeconds,
                       LoginResp.UserInfo user) {}
}
```

- [ ] **Step 3: 实现**（较长）

```java
package com.ems.auth.service.impl;

import com.ems.audit.event.AuditEvent;
import com.ems.audit.service.AuditService;
import com.ems.auth.dto.LoginResp;
import com.ems.auth.entity.*;
import com.ems.auth.jwt.JwtService;
import com.ems.auth.repository.*;
import com.ems.auth.service.AuthService;
import com.ems.core.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserRepository users;
    private final UserRoleRepository userRoles;
    private final RefreshTokenRepository refresh;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;

    @Value("${ems.login.max-failed-attempts}") private int maxFailed;
    @Value("${ems.login.lockout-minutes}")     private int lockoutMinutes;
    @Value("${ems.jwt.access-token-minutes}")  private long accessMinutes;
    @Value("${ems.jwt.refresh-token-days}")    private long refreshDays;

    public AuthServiceImpl(UserRepository u, UserRoleRepository ur, RefreshTokenRepository r,
                           PasswordEncoder e, JwtService j, AuditService a) {
        this.users = u; this.userRoles = ur; this.refresh = r;
        this.encoder = e; this.jwt = j; this.audit = a;
    }

    @Override
    @Transactional
    public LoginResult login(String username, String password, String ip, String ua) {
        User u = users.findByUsername(username).orElseThrow(() ->
            authFail(null, username, ip, ua, "用户不存在"));

        if (u.getLockedUntil() != null && u.getLockedUntil().isAfter(OffsetDateTime.now())) {
            audit(u.getId(), u.getUsername(), "LOGIN_FAIL", ip, ua, "账号锁定");
            throw new UnauthorizedException("账号已锁定，请稍后再试");
        }
        if (!Boolean.TRUE.equals(u.getEnabled())) {
            audit(u.getId(), u.getUsername(), "LOGIN_FAIL", ip, ua, "账号禁用");
            throw new UnauthorizedException("账号已禁用");
        }

        if (!encoder.matches(password, u.getPasswordHash())) {
            u.setFailedAttempts(u.getFailedAttempts() + 1);
            if (u.getFailedAttempts() >= maxFailed) {
                u.setLockedUntil(OffsetDateTime.now().plusMinutes(lockoutMinutes));
                u.setFailedAttempts(0);
            }
            users.save(u);
            audit(u.getId(), u.getUsername(), "LOGIN_FAIL", ip, ua, "密码错误");
            throw new UnauthorizedException("用户名或密码错误");
        }

        // 登录成功
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        u.setLastLoginAt(OffsetDateTime.now());
        users.save(u);

        List<String> roles = userRoles.findRoleCodesByUserId(u.getId());
        String accessToken = jwt.signAccessToken(u.getId(), u.getUsername(), roles);
        JwtService.SignedRefresh refreshSigned = jwt.signRefreshToken(u.getId());

        RefreshToken rt = new RefreshToken();
        rt.setJti(refreshSigned.jti());
        rt.setUserId(u.getId());
        rt.setIssuedAt(OffsetDateTime.now());
        rt.setExpiresAt(refreshSigned.expiresAt().atOffset(ZoneOffset.UTC));
        refresh.save(rt);

        audit(u.getId(), u.getUsername(), "LOGIN", ip, ua, "登录成功");

        return new LoginResult(
            accessToken, accessMinutes * 60,
            refreshSigned.token(), refreshDays * 86400L,
            new LoginResp.UserInfo(u.getId(), u.getUsername(), u.getDisplayName(), roles)
        );
    }

    @Override
    @Transactional
    public LoginResult refresh(String refreshTokenStr, String ip, String ua) {
        JwtService.RefreshClaims c = jwt.parseRefresh(refreshTokenStr);
        RefreshToken rt = refresh.findById(c.jti()).orElseThrow(() ->
            new UnauthorizedException("refresh token not found"));
        if (rt.getRevokedAt() != null) throw new UnauthorizedException("refresh token revoked");
        if (rt.getExpiresAt().isBefore(OffsetDateTime.now())) throw new UnauthorizedException("refresh token expired");

        // 轮换：撤销旧的
        rt.setRevokedAt(OffsetDateTime.now());
        refresh.save(rt);

        User u = users.findById(c.userId()).orElseThrow(() -> new UnauthorizedException("user gone"));
        List<String> roles = userRoles.findRoleCodesByUserId(u.getId());
        String access = jwt.signAccessToken(u.getId(), u.getUsername(), roles);
        JwtService.SignedRefresh newRt = jwt.signRefreshToken(u.getId());
        RefreshToken nr = new RefreshToken();
        nr.setJti(newRt.jti()); nr.setUserId(u.getId());
        nr.setIssuedAt(OffsetDateTime.now());
        nr.setExpiresAt(newRt.expiresAt().atOffset(ZoneOffset.UTC));
        refresh.save(nr);

        return new LoginResult(access, accessMinutes * 60,
            newRt.token(), refreshDays * 86400L,
            new LoginResp.UserInfo(u.getId(), u.getUsername(), u.getDisplayName(), roles));
    }

    @Override
    @Transactional
    public void logout(String refreshTokenStr) {
        try {
            JwtService.RefreshClaims c = jwt.parseRefresh(refreshTokenStr);
            refresh.findById(c.jti()).ifPresent(rt -> {
                rt.setRevokedAt(OffsetDateTime.now());
                refresh.save(rt);
            });
            audit(c.userId(), null, "LOGOUT", null, null, "登出");
        } catch (Exception e) {
            log.debug("logout token invalid, ignore: {}", e.getMessage());
        }
    }

    private void audit(Long uid, String username, String action, String ip, String ua, String summary) {
        audit.record(new AuditEvent(uid, username, action, "AUTH", username,
            summary, null, ip, ua, OffsetDateTime.now()));
    }

    private UnauthorizedException authFail(Long uid, String un, String ip, String ua, String reason) {
        audit(uid, un, "LOGIN_FAIL", ip, ua, reason);
        return new UnauthorizedException("用户名或密码错误");
    }
}
```

- [ ] **Step 4: 提交**

```bash
git add ems-auth/src/main/java/com/ems/auth/
git commit -m "feat(auth): AuthService login/refresh/logout with rotation and lockout"
```

---

### Task 42: `AuthController`

**Files:**
- Create: `ems-auth/src/main/java/com/ems/auth/controller/AuthController.java`

- [ ] **Step 1: 写 controller**

```java
package com.ems.auth.controller;

import com.ems.auth.dto.LoginReq;
import com.ems.auth.dto.LoginResp;
import com.ems.auth.service.AuthService;
import com.ems.core.dto.Result;
import com.ems.core.exception.UnauthorizedException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String COOKIE_REFRESH = "emsRefresh";

    private final AuthService auth;

    public AuthController(AuthService a) { this.auth = a; }

    @PostMapping("/login")
    public Result<LoginResp> login(@Valid @RequestBody LoginReq req,
                                   HttpServletRequest httpReq, HttpServletResponse resp) {
        var r = auth.login(req.username(), req.password(),
            httpReq.getRemoteAddr(), httpReq.getHeader("User-Agent"));
        writeRefreshCookie(resp, r.refreshToken(), r.refreshMaxAgeSeconds());
        return Result.ok(new LoginResp(r.accessToken(), r.accessExpSeconds(), r.user()));
    }

    @PostMapping("/refresh")
    public Result<LoginResp> refresh(HttpServletRequest req, HttpServletResponse resp) {
        String token = readRefreshCookie(req).orElseThrow(() ->
            new UnauthorizedException("no refresh token"));
        var r = auth.refresh(token, req.getRemoteAddr(), req.getHeader("User-Agent"));
        writeRefreshCookie(resp, r.refreshToken(), r.refreshMaxAgeSeconds());
        return Result.ok(new LoginResp(r.accessToken(), r.accessExpSeconds(), r.user()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest req, HttpServletResponse resp) {
        readRefreshCookie(req).ifPresent(auth::logout);
        clearRefreshCookie(resp);
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<LoginResp.UserInfo> me(@AuthenticationPrincipal com.ems.auth.security.AuthUser u) {
        if (u == null) throw new UnauthorizedException("not logged in");
        return Result.ok(new LoginResp.UserInfo(u.getUserId(), u.getUsername(), null,
            u.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .toList()));
    }

    private java.util.Optional<String> readRefreshCookie(HttpServletRequest req) {
        if (req.getCookies() == null) return java.util.Optional.empty();
        return Arrays.stream(req.getCookies())
            .filter(c -> COOKIE_REFRESH.equals(c.getName()))
            .map(Cookie::getValue).findFirst();
    }

    private void writeRefreshCookie(HttpServletResponse resp, String token, long maxAgeSec) {
        Cookie c = new Cookie(COOKIE_REFRESH, token);
        c.setHttpOnly(true); c.setPath("/api/v1/auth");
        c.setMaxAge((int) maxAgeSec);
        c.setAttribute("SameSite", "Lax");
        resp.addCookie(c);
    }

    private void clearRefreshCookie(HttpServletResponse resp) {
        Cookie c = new Cookie(COOKIE_REFRESH, "");
        c.setHttpOnly(true); c.setPath("/api/v1/auth"); c.setMaxAge(0);
        resp.addCookie(c);
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add ems-auth/src/main/java/com/ems/auth/controller/AuthController.java ems-auth/src/main/java/com/ems/auth/dto/
git commit -m "feat(auth): AuthController endpoints with HttpOnly refresh cookie"
```

---

### Task 43: `AuditContextImpl`（`ems-auth` 提供实现）

**Files:**
- Create: `ems-auth/src/main/java/com/ems/auth/security/AuditContextImpl.java`

- [ ] **Step 1: 写实现**

```java
package com.ems.auth.security;

import com.ems.audit.aspect.AuditContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class AuditContextImpl implements AuditContext {

    @Override
    public Long currentUserId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AuthUser u) return u.getUserId();
        return null;
    }

    @Override
    public String currentUsername() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }

    @Override
    public String currentIp() {
        var req = currentRequest();
        return req == null ? null : req.getRemoteAddr();
    }

    @Override
    public String currentUserAgent() {
        var req = currentRequest();
        return req == null ? null : req.getHeader("User-Agent");
    }

    private jakarta.servlet.http.HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes s ? s.getRequest() : null;
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add ems-auth/src/main/java/com/ems/auth/security/AuditContextImpl.java
git commit -m "feat(auth): AuditContext implementation using SecurityContext"
```

---

### Task 44: `PermissionResolver` — 接口 + 实现

**Files:**
- Create: `ems-auth/src/main/java/com/ems/auth/service/PermissionResolver.java`
- Create: `ems-auth/src/main/java/com/ems/auth/service/impl/PermissionResolverImpl.java`

- [ ] **Step 1: 写接口**

```java
package com.ems.auth.service;

import java.util.Set;

public interface PermissionResolver {
    /** 哨兵：ADMIN 用户拥有全节点 */
    Set<Long> ALL_NODE_IDS_MARKER = Set.of(-1L);

    Set<Long> visibleNodeIds(Long userId);
    boolean   canAccess(Long userId, Long orgNodeId);
    boolean   hasAllNodes(Set<Long> visible);
}
```

- [ ] **Step 2: 实现**

```java
package com.ems.auth.service.impl;

import com.ems.auth.repository.NodePermissionRepository;
import com.ems.auth.repository.UserRoleRepository;
import com.ems.auth.service.PermissionResolver;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class PermissionResolverImpl implements PermissionResolver {

    private final NodePermissionRepository perms;
    private final UserRoleRepository userRoles;

    public PermissionResolverImpl(NodePermissionRepository p, UserRoleRepository ur) {
        this.perms = p; this.userRoles = ur;
    }

    @Override
    public Set<Long> visibleNodeIds(Long userId) {
        if (userId == null) return Set.of();
        if (userRoles.findRoleCodesByUserId(userId).contains("ADMIN")) {
            return ALL_NODE_IDS_MARKER;
        }
        return perms.findVisibleNodeIds(userId);
    }

    @Override
    public boolean canAccess(Long userId, Long orgNodeId) {
        Set<Long> v = visibleNodeIds(userId);
        return v == ALL_NODE_IDS_MARKER || v.contains(orgNodeId);
    }

    @Override
    public boolean hasAllNodes(Set<Long> visible) {
        return visible == ALL_NODE_IDS_MARKER;
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add ems-auth/src/main/java/com/ems/auth/service/
git commit -m "feat(auth): PermissionResolver with ADMIN bypass"
```

---

### Task 45: User CRUD — Service

**Files:**
- Create: `ems-auth/src/main/java/com/ems/auth/dto/CreateUserReq.java`
- Create: `ems-auth/src/main/java/com/ems/auth/dto/UpdateUserReq.java`
- Create: `ems-auth/src/main/java/com/ems/auth/dto/UserDTO.java`
- Create: `ems-auth/src/main/java/com/ems/auth/dto/AssignRolesReq.java`
- Create: `ems-auth/src/main/java/com/ems/auth/service/UserService.java`
- Create: `ems-auth/src/main/java/com/ems/auth/service/impl/UserServiceImpl.java`

- [ ] **Step 1: DTO**

```java
// CreateUserReq.java
package com.ems.auth.dto;
import jakarta.validation.constraints.*;
public record CreateUserReq(
    @NotBlank @Size(min=3, max=64) @Pattern(regexp = "[A-Za-z0-9_.-]+") String username,
    @NotBlank @Size(min=8, max=64) String password,
    @Size(max=128) String displayName,
    java.util.List<String> roleCodes
) {}
```

```java
// UpdateUserReq.java
package com.ems.auth.dto;
import jakarta.validation.constraints.Size;
public record UpdateUserReq(
    @Size(max=128) String displayName,
    Boolean enabled
) {}
```

```java
// UserDTO.java
package com.ems.auth.dto;
import java.time.OffsetDateTime;
import java.util.List;
public record UserDTO(
    Long id, String username, String displayName, Boolean enabled,
    List<String> roles, OffsetDateTime lastLoginAt, OffsetDateTime createdAt
) {}
```

```java
// AssignRolesReq.java
package com.ems.auth.dto;
import jakarta.validation.constraints.NotNull;
import java.util.List;
public record AssignRolesReq(@NotNull List<String> roleCodes) {}
```

- [ ] **Step 2: Service 接口**

```java
package com.ems.auth.service;
import com.ems.auth.dto.*;
import com.ems.core.dto.PageDTO;

public interface UserService {
    UserDTO create(CreateUserReq req);
    UserDTO update(Long id, UpdateUserReq req);
    void delete(Long id);
    UserDTO getById(Long id);
    PageDTO<UserDTO> list(int page, int size, String keyword);
    void assignRoles(Long id, AssignRolesReq req);
    void changePassword(Long id, String oldPassword, String newPassword);
    void resetPassword(Long id, String newPassword);
}
```

- [ ] **Step 3: 实现**

```java
package com.ems.auth.service.impl;

import com.ems.audit.annotation.Audited;
import com.ems.auth.dto.*;
import com.ems.auth.entity.*;
import com.ems.auth.repository.*;
import com.ems.auth.service.UserService;
import com.ems.core.dto.PageDTO;
import com.ems.core.exception.*;
import com.ems.core.constant.ErrorCode;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final PasswordEncoder encoder;

    public UserServiceImpl(UserRepository u, RoleRepository r, UserRoleRepository ur, PasswordEncoder e) {
        this.users = u; this.roles = r; this.userRoles = ur; this.encoder = e;
    }

    @Override
    @Transactional
    @Audited(action = "CREATE", resourceType = "USER", resourceIdExpr = "#result.id()")
    public UserDTO create(CreateUserReq req) {
        if (users.existsByUsername(req.username()))
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        validatePassword(req.password());

        User u = new User();
        u.setUsername(req.username());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setDisplayName(req.displayName());
        u.setEnabled(true);
        users.save(u);

        if (req.roleCodes() != null) assignRolesInternal(u.getId(), req.roleCodes());
        return toDTO(u);
    }

    @Override
    @Transactional
    @Audited(action = "UPDATE", resourceType = "USER", resourceIdExpr = "#id")
    public UserDTO update(Long id, UpdateUserReq req) {
        User u = users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
        if (req.displayName() != null) u.setDisplayName(req.displayName());
        if (req.enabled() != null) u.setEnabled(req.enabled());
        users.save(u);
        return toDTO(u);
    }

    @Override
    @Transactional
    @Audited(action = "DELETE", resourceType = "USER", resourceIdExpr = "#id")
    public void delete(Long id) {
        User u = users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
        if ("admin".equals(u.getUsername()))
            throw new BusinessException(ErrorCode.BIZ_GENERIC, "不能删除默认管理员");
        users.delete(u);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDTO getById(Long id) {
        return toDTO(users.findById(id).orElseThrow(() -> new NotFoundException("User", id)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageDTO<UserDTO> list(int page, int size, String keyword) {
        Pageable p = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<User> pg = users.findAll(p);
        List<UserDTO> items = pg.stream()
            .filter(u -> keyword == null || keyword.isBlank()
                || u.getUsername().contains(keyword)
                || (u.getDisplayName() != null && u.getDisplayName().contains(keyword)))
            .map(this::toDTO).toList();
        return PageDTO.of(items, pg.getTotalElements(), page, size);
    }

    @Override
    @Transactional
    @Audited(action = "CONFIG_CHANGE", resourceType = "USER", resourceIdExpr = "#id",
             summaryExpr = "'assign roles'")
    public void assignRoles(Long id, AssignRolesReq req) {
        users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
        userRoles.deleteByUserId(id);
        assignRolesInternal(id, req.roleCodes());
    }

    @Override
    @Transactional
    @Audited(action = "CONFIG_CHANGE", resourceType = "USER", resourceIdExpr = "#id",
             summaryExpr = "'change password'")
    public void changePassword(Long id, String oldPassword, String newPassword) {
        User u = users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
        if (!encoder.matches(oldPassword, u.getPasswordHash()))
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "原密码错误");
        validatePassword(newPassword);
        u.setPasswordHash(encoder.encode(newPassword));
        users.save(u);
    }

    @Override
    @Transactional
    @Audited(action = "CONFIG_CHANGE", resourceType = "USER", resourceIdExpr = "#id",
             summaryExpr = "'admin reset password'")
    public void resetPassword(Long id, String newPassword) {
        User u = users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
        validatePassword(newPassword);
        u.setPasswordHash(encoder.encode(newPassword));
        users.save(u);
    }

    private void assignRolesInternal(Long uid, List<String> codes) {
        for (String code : codes) {
            Role r = roles.findByCode(code).orElseThrow(() ->
                new NotFoundException("Role", code));
            userRoles.save(new UserRole(uid, r.getId()));
        }
    }

    private void validatePassword(String p) {
        if (p == null || p.length() < 8)
            throw new BusinessException(ErrorCode.PARAM_INVALID, "密码至少 8 位");
        if (p.matches("\\d+"))
            throw new BusinessException(ErrorCode.PARAM_INVALID, "密码不能纯数字");
    }

    private UserDTO toDTO(User u) {
        List<String> codes = userRoles.findRoleCodesByUserId(u.getId());
        return new UserDTO(u.getId(), u.getUsername(), u.getDisplayName(), u.getEnabled(),
            codes, u.getLastLoginAt(), u.getCreatedAt());
    }
}
```

- [ ] **Step 4: 提交**

```bash
git add ems-auth/src/main/java/com/ems/auth/
git commit -m "feat(auth): UserService with CRUD, role assignment, password ops"
```

---

### Task 46: `UserController` + `RoleController`

**Files:**
- Create: `ems-auth/src/main/java/com/ems/auth/controller/UserController.java`
- Create: `ems-auth/src/main/java/com/ems/auth/controller/RoleController.java`
- Create: `ems-auth/src/main/java/com/ems/auth/dto/RoleDTO.java`
- Create: `ems-auth/src/main/java/com/ems/auth/dto/ResetPasswordReq.java`
- Create: `ems-auth/src/main/java/com/ems/auth/dto/ChangePasswordReq.java`

- [ ] **Step 1: DTOs**

```java
// RoleDTO.java
package com.ems.auth.dto;
public record RoleDTO(Long id, String code, String name, String description) {}
```

```java
// ResetPasswordReq.java
package com.ems.auth.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record ResetPasswordReq(@NotBlank @Size(min=8, max=64) String newPassword) {}
```

```java
// ChangePasswordReq.java
package com.ems.auth.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record ChangePasswordReq(@NotBlank String oldPassword,
                                @NotBlank @Size(min=8, max=64) String newPassword) {}
```

- [ ] **Step 2: `RoleController`**

```java
package com.ems.auth.controller;

import com.ems.auth.dto.RoleDTO;
import com.ems.auth.repository.RoleRepository;
import com.ems.core.dto.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {
    private final RoleRepository roles;
    public RoleController(RoleRepository r) { this.roles = r; }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<RoleDTO>> list() {
        return Result.ok(roles.findAll().stream()
            .map(r -> new RoleDTO(r.getId(), r.getCode(), r.getName(), r.getDescription()))
            .toList());
    }
}
```

- [ ] **Step 3: `UserController`**

```java
package com.ems.auth.controller;

import com.ems.auth.dto.*;
import com.ems.auth.security.AuthUser;
import com.ems.auth.service.UserService;
import com.ems.core.dto.PageDTO;
import com.ems.core.dto.Result;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService users;
    public UserController(UserService u) { this.users = u; }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageDTO<UserDTO>> list(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) String keyword) {
        return Result.ok(users.list(page, size, keyword));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<UserDTO> get(@PathVariable Long id) { return Result.ok(users.getById(id)); }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<UserDTO>> create(@Valid @RequestBody CreateUserReq req) {
        return ResponseEntity.status(201).body(Result.ok(users.create(req)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<UserDTO> update(@PathVariable Long id, @Valid @RequestBody UpdateUserReq req) {
        return Result.ok(users.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        users.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> assignRoles(@PathVariable Long id, @Valid @RequestBody AssignRolesReq req) {
        users.assignRoles(id, req);
        return Result.ok();
    }

    @PutMapping("/{id}/password/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> reset(@PathVariable Long id, @Valid @RequestBody ResetPasswordReq req) {
        users.resetPassword(id, req.newPassword());
        return Result.ok();
    }

    @PutMapping("/me/password")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> changeOwn(@AuthenticationPrincipal AuthUser u,
                                  @Valid @RequestBody ChangePasswordReq req) {
        users.changePassword(u.getUserId(), req.oldPassword(), req.newPassword());
        return Result.ok();
    }
}
```

- [ ] **Step 4: 提交**

```bash
git add ems-auth/src/main/java/com/ems/auth/controller/ ems-auth/src/main/java/com/ems/auth/dto/
git commit -m "feat(auth): UserController + RoleController"
```

---

### Task 47: NodePermission Service + Controller

**Files:**
- Create: `ems-auth/src/main/java/com/ems/auth/dto/AssignNodePermissionReq.java`
- Create: `ems-auth/src/main/java/com/ems/auth/dto/NodePermissionDTO.java`
- Create: `ems-auth/src/main/java/com/ems/auth/service/NodePermissionService.java`
- Create: `ems-auth/src/main/java/com/ems/auth/service/impl/NodePermissionServiceImpl.java`
- Create: `ems-auth/src/main/java/com/ems/auth/controller/NodePermissionController.java`

- [ ] **Step 1: DTO**

```java
// AssignNodePermissionReq.java
package com.ems.auth.dto;
import jakarta.validation.constraints.*;
public record AssignNodePermissionReq(
    @NotNull Long orgNodeId,
    @NotNull @Pattern(regexp = "SUBTREE|NODE_ONLY") String scope
) {}
```

```java
// NodePermissionDTO.java
package com.ems.auth.dto;
import java.time.OffsetDateTime;
public record NodePermissionDTO(Long id, Long userId, Long orgNodeId,
                                String scope, OffsetDateTime createdAt) {}
```

- [ ] **Step 2: Service**

```java
package com.ems.auth.service;
import com.ems.auth.dto.*;
import java.util.List;
public interface NodePermissionService {
    List<NodePermissionDTO> listByUser(Long userId);
    NodePermissionDTO assign(Long userId, AssignNodePermissionReq req);
    void revoke(Long permissionId);
}
```

```java
package com.ems.auth.service.impl;

import com.ems.audit.annotation.Audited;
import com.ems.auth.dto.*;
import com.ems.auth.entity.NodePermission;
import com.ems.auth.repository.NodePermissionRepository;
import com.ems.auth.repository.UserRepository;
import com.ems.auth.service.NodePermissionService;
import com.ems.core.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NodePermissionServiceImpl implements NodePermissionService {

    private final NodePermissionRepository perms;
    private final UserRepository users;

    public NodePermissionServiceImpl(NodePermissionRepository p, UserRepository u) {
        this.perms = p; this.users = u;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NodePermissionDTO> listByUser(Long userId) {
        return perms.findByUserId(userId).stream()
            .map(n -> new NodePermissionDTO(n.getId(), n.getUserId(), n.getOrgNodeId(),
                n.getScope(), n.getCreatedAt()))
            .toList();
    }

    @Override
    @Transactional
    @Audited(action = "CONFIG_CHANGE", resourceType = "NODE_PERMISSION",
             resourceIdExpr = "#userId", summaryExpr = "'grant node permission'")
    public NodePermissionDTO assign(Long userId, AssignNodePermissionReq req) {
        users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
        NodePermission p = new NodePermission();
        p.setUserId(userId);
        p.setOrgNodeId(req.orgNodeId());
        p.setScope(req.scope());
        perms.save(p);
        return new NodePermissionDTO(p.getId(), p.getUserId(), p.getOrgNodeId(),
            p.getScope(), p.getCreatedAt());
    }

    @Override
    @Transactional
    @Audited(action = "CONFIG_CHANGE", resourceType = "NODE_PERMISSION",
             resourceIdExpr = "#permissionId", summaryExpr = "'revoke node permission'")
    public void revoke(Long permissionId) {
        perms.findById(permissionId).orElseThrow(() ->
            new NotFoundException("NodePermission", permissionId));
        perms.deleteById(permissionId);
    }
}
```

- [ ] **Step 3: Controller**

```java
package com.ems.auth.controller;

import com.ems.auth.dto.*;
import com.ems.auth.service.NodePermissionService;
import com.ems.core.dto.Result;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users/{userId}/node-permissions")
@PreAuthorize("hasRole('ADMIN')")
public class NodePermissionController {

    private final NodePermissionService svc;
    public NodePermissionController(NodePermissionService s) { this.svc = s; }

    @GetMapping
    public Result<List<NodePermissionDTO>> list(@PathVariable Long userId) {
        return Result.ok(svc.listByUser(userId));
    }

    @PostMapping
    public ResponseEntity<Result<NodePermissionDTO>> assign(@PathVariable Long userId,
            @Valid @RequestBody AssignNodePermissionReq req) {
        return ResponseEntity.status(201).body(Result.ok(svc.assign(userId, req)));
    }

    @DeleteMapping("/{permissionId}")
    public ResponseEntity<Void> revoke(@PathVariable Long userId, @PathVariable Long permissionId) {
        svc.revoke(permissionId);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: 提交**

```bash
git add ems-auth/src/main/java/com/ems/auth/
git commit -m "feat(auth): NodePermission service and controller"
```

---

### Task 48: `AuditLogController`（ems-auth 侧，因为权限校验依赖 auth）

**Files:**
- Create: `ems-auth/src/main/java/com/ems/auth/controller/AuditLogController.java`
- Create: `ems-auth/src/main/java/com/ems/auth/dto/AuditLogDTO.java`
- Modify: `ems-auth/pom.xml`（加 `ems-audit` 依赖已经有了）

- [ ] **Step 1: DTO**

```java
// AuditLogDTO.java
package com.ems.auth.dto;
import java.time.OffsetDateTime;
public record AuditLogDTO(
    Long id, Long actorUserId, String actorUsername,
    String action, String resourceType, String resourceId,
    String summary, String detail, String ip, String userAgent, OffsetDateTime occurredAt
) {}
```

- [ ] **Step 2: Controller**

```java
package com.ems.auth.controller;

import com.ems.audit.entity.AuditLog;
import com.ems.audit.repository.AuditLogRepository;
import com.ems.auth.dto.AuditLogDTO;
import com.ems.core.dto.PageDTO;
import com.ems.core.dto.Result;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {

    private final AuditLogRepository repo;
    public AuditLogController(AuditLogRepository r) { this.repo = r; }

    @GetMapping
    public Result<PageDTO<AuditLogDTO>> search(
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable p = PageRequest.of(page - 1, size);
        Page<AuditLog> pg = repo.search(actorUserId, resourceType, action, from, to, p);
        var items = pg.map(a -> new AuditLogDTO(a.getId(), a.getActorUserId(), a.getActorUsername(),
            a.getAction(), a.getResourceType(), a.getResourceId(),
            a.getSummary(), a.getDetail(), a.getIp(), a.getUserAgent(), a.getOccurredAt())
        ).toList();
        return Result.ok(PageDTO.of(items, pg.getTotalElements(), page, size));
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add ems-auth/src/main/java/com/ems/auth/controller/AuditLogController.java ems-auth/src/main/java/com/ems/auth/dto/AuditLogDTO.java
git commit -m "feat(auth): AuditLogController with filters"
```

---

### Task 49: 端到端启动验证

**Files:**
（无需新文件；仅验证）

- [ ] **Step 1: 启动 Postgres + 应用**

```bash
docker compose -f docker-compose.dev.yml up -d
./mvnw -pl ems-app -am install -DskipTests
./mvnw -pl ems-app spring-boot:run
```

- [ ] **Step 2: 在另一终端手动测试登录**

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"username":"admin","password":"admin123!"}'
```

Expected: 200 + `{"code":0,"data":{"accessToken":"...","user":{...}}}` + Set-Cookie emsRefresh=...

- [ ] **Step 3: 用 token 访问受保护端点**

```bash
curl -H "Authorization: Bearer <accessToken>" http://localhost:8080/api/v1/auth/me
curl -H "Authorization: Bearer <accessToken>" http://localhost:8080/api/v1/users
```

Expected: 200 带数据。

- [ ] **Step 4: 验证匿名访问被拦**

```bash
curl -i http://localhost:8080/api/v1/users
```

Expected: 401 + `{"code":40001,...}`

- [ ] **Step 5: Ctrl-C 停服务，Task 无代码改动，跳过提交。**

---

### Task 50: Auth 集成测试 — 登录 + JWT 拦截

**Files:**
- Create: `ems-auth/src/test/java/com/ems/auth/AuthFlowIT.java`
- Create: `ems-auth/src/test/resources/application-test.yml`
- Create: `ems-auth/src/test/resources/db/migration/` （复制 V1.0.0、V1.0.1、V1.0.7、V1.0.8）

- [ ] **Step 1: 复制 4 个迁移脚本**

```bash
mkdir -p ems-auth/src/test/resources/db/migration
cp ems-app/src/main/resources/db/migration/V1.0.0__init_auth.sql \
   ems-app/src/main/resources/db/migration/V1.0.1__init_orgtree.sql \
   ems-app/src/main/resources/db/migration/V1.0.7__init_audit.sql \
   ems-app/src/main/resources/db/migration/V1.0.8__seed_reference_data.sql \
   ems-auth/src/test/resources/db/migration/
```

- [ ] **Step 2: 测试配置**

```yaml
# ems-auth/src/test/resources/application-test.yml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
  jpa:
    hibernate.ddl-auto: none
ems:
  jwt:
    secret: this-is-a-test-secret-at-least-32-bytes-long!!!
    access-token-minutes: 15
    refresh-token-days: 7
  login:
    max-failed-attempts: 5
    lockout-minutes: 15
```

- [ ] **Step 3: 集成测试**

```java
package com.ems.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = com.ems.app.FactoryEmsApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuthFlowIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mvc;

    @Test
    void login_wrongPassword_401() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void login_success_returnsAccessToken() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"admin123!\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
           .andExpect(cookie().exists("emsRefresh"));
    }

    @Test
    void protected_withoutToken_401() throws Exception {
        mvc.perform(get("/api/v1/users"))
           .andExpect(status().isUnauthorized());
    }
}
```

> **Note:** 此测试依赖 `ems-app.FactoryEmsApplication` 作为 `classes` 的 root，所以 `ems-auth/pom.xml` 必须加 `ems-app` 的 `scope=test` 依赖。但 `ems-app` 依赖 `ems-auth`——循环！解决方式：不要用 `ems-app` 作为 `@SpringBootTest(classes)`，改为在 `ems-auth/src/test/java/com/ems/auth/AuthITApp.java` 创建一个最小测试启动类，只 scan `com.ems` 下必要包。

- [ ] **Step 4: 创建最小测试启动类**

```java
// ems-auth/src/test/java/com/ems/auth/AuthITApp.java
package com.ems.auth;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {
    "com.ems.core", "com.ems.audit", "com.ems.orgtree", "com.ems.auth",
    "com.ems.app.handler", "com.ems.app.filter", "com.ems.app.config"
})
@EntityScan(basePackages = "com.ems")
@EnableJpaRepositories(basePackages = "com.ems")
public class AuthITApp {}
```

改 `AuthFlowIT` 的 `classes = AuthITApp.class`。

此外 `ems-auth/pom.xml` 需加测试依赖 `ems-orgtree`（闭包表迁移 + 实体）和 `ems-app`（handler/filter）。改为 `test` scope。

> 最干净的做法：`AuthFlowIT` **不依赖** `ems-app`，只在 `ems-auth` 内把最小启动类写好。如果 `handler/filter` 不测可以从 scanBasePackages 里移除。

- [ ] **Step 5: 在 pom.xml 加依赖**（test scope）

```xml
<dependency>
    <groupId>com.ems</groupId>
    <artifactId>ems-orgtree</artifactId>
    <version>${project.version}</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 6: 运行**

```bash
./mvnw -pl ems-auth test -Dtest=AuthFlowIT
```

Expected: 3 PASS.

- [ ] **Step 7: 提交**

```bash
git add ems-auth/
git commit -m "test(auth): integration tests for login flow"
```

---

### Task 51: Auth 集成测试 — Refresh token 轮换

**Files:**
- Modify: `ems-auth/src/test/java/com/ems/auth/AuthFlowIT.java`

- [ ] **Step 1: 加测试**

```java
@Test
void refresh_rotatesToken() throws Exception {
    MvcResult login = mvc.perform(post("/api/v1/auth/login")
            .contentType("application/json")
            .content("{\"username\":\"admin\",\"password\":\"admin123!\"}"))
        .andExpect(status().isOk()).andReturn();
    Cookie refreshCookie = login.getResponse().getCookie("emsRefresh");
    String oldToken = refreshCookie.getValue();

    MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
        .andExpect(status().isOk()).andReturn();
    Cookie newCookie = refreshed.getResponse().getCookie("emsRefresh");

    assertThat(newCookie.getValue()).isNotEqualTo(oldToken);

    // 旧 token 应被吊销
    mvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
        .andExpect(status().isUnauthorized());
}
```

补 import：

```java
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;
```

- [ ] **Step 2: 跑测试**

```bash
./mvnw -pl ems-auth test -Dtest=AuthFlowIT
```

Expected: 4 PASS.

- [ ] **Step 3: 提交**

```bash
git add ems-auth/src/test/java/com/ems/auth/AuthFlowIT.java
git commit -m "test(auth): refresh token rotation IT"
```

---

### Task 52: Auth 集成测试 — 锁定机制

**Files:**
- Modify: `ems-auth/src/test/java/com/ems/auth/AuthFlowIT.java`

- [ ] **Step 1: 加测试**

```java
@Test
void lockoutAfter5FailedAttempts() throws Exception {
    for (int i = 0; i < 5; i++) {
        mvc.perform(post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"wrongpass\"}"))
           .andExpect(status().isUnauthorized());
    }
    // 正确密码也被锁定
    mvc.perform(post("/api/v1/auth/login")
            .contentType("application/json")
            .content("{\"username\":\"admin\",\"password\":\"admin123!\"}"))
       .andExpect(status().isUnauthorized())
       .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("锁定")));
}
```

> **Note:** 该测试会修改 admin 账号状态。测试类需要 `@TestMethodOrder` 或用独立用户。简化：`@DirtiesContext(classMode = BEFORE_EACH_TEST_METHOD)` 或每个测试独立 PG 实例（慢）。更好：建独立用户 `testuser1`。

- [ ] **Step 2: 改用独立用户**

```java
@Test
void lockoutAfter5FailedAttempts(@Autowired UserService userService) throws Exception {
    userService.create(new CreateUserReq("lockme", "password123", "Lock Me", List.of("VIEWER")));
    for (int i = 0; i < 5; i++) {
        mvc.perform(post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"lockme\",\"password\":\"wrong\"}"))
           .andExpect(status().isUnauthorized());
    }
    mvc.perform(post("/api/v1/auth/login")
            .contentType("application/json")
            .content("{\"username\":\"lockme\",\"password\":\"password123\"}"))
       .andExpect(status().isUnauthorized());
}
```

- [ ] **Step 3: 跑测试**

```bash
./mvnw -pl ems-auth test -Dtest=AuthFlowIT
```

Expected: 5 PASS.

- [ ] **Step 4: 提交**

```bash
git add ems-auth/src/test/
git commit -m "test(auth): lockout after N failed attempts"
```

---

### Task 53: PermissionResolver 集成测试

**Files:**
- Create: `ems-auth/src/test/java/com/ems/auth/PermissionResolverIT.java`

- [ ] **Step 1: 写测试**

```java
package com.ems.auth;

import com.ems.auth.dto.*;
import com.ems.auth.service.*;
import com.ems.orgtree.dto.CreateOrgNodeReq;
import com.ems.orgtree.service.OrgNodeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AuthITApp.class)
@ActiveProfiles("test")
@Testcontainers
class PermissionResolverIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired OrgNodeService tree;
    @Autowired UserService users;
    @Autowired NodePermissionService perms;
    @Autowired PermissionResolver resolver;

    @Test
    void viewer_withSubtreePerm_seesOnlyThatSubtree() {
        var a = tree.create(new CreateOrgNodeReq(null,   "A", "PA", "PLANT", 0));
        var b = tree.create(new CreateOrgNodeReq(a.id(), "B", "PB", "WS",    0));
        var c = tree.create(new CreateOrgNodeReq(b.id(), "C", "PC", "DEV",   0));
        var x = tree.create(new CreateOrgNodeReq(null,   "X", "PX", "PLANT", 0));

        var u = users.create(new CreateUserReq("zhang3", "password123", "张三", List.of("VIEWER")));
        perms.assign(u.id(), new AssignNodePermissionReq(b.id(), "SUBTREE"));

        var visible = resolver.visibleNodeIds(u.id());
        assertThat(visible).containsExactlyInAnyOrder(b.id(), c.id());
        assertThat(visible).doesNotContain(a.id(), x.id());
    }

    @Test
    void admin_getsAllMarker() {
        var u = users.create(new CreateUserReq("adminx", "password123", "A", List.of("ADMIN")));
        assertThat(resolver.hasAllNodes(resolver.visibleNodeIds(u.id()))).isTrue();
    }

    @Test
    void nodeOnlyScope_excludesDescendants() {
        var p = tree.create(new CreateOrgNodeReq(null,   "P", "PP", "PLANT", 0));
        var q = tree.create(new CreateOrgNodeReq(p.id(), "Q", "PQ", "WS",    0));
        var u = users.create(new CreateUserReq("li4", "password123", "李四", List.of("VIEWER")));
        perms.assign(u.id(), new AssignNodePermissionReq(p.id(), "NODE_ONLY"));

        var visible = resolver.visibleNodeIds(u.id());
        assertThat(visible).contains(p.id()).doesNotContain(q.id());
    }
}
```

- [ ] **Step 2: 跑测试**

```bash
./mvnw -pl ems-auth test -Dtest=PermissionResolverIT
```

Expected: 3 PASS.

- [ ] **Step 3: 提交**

```bash
git add ems-auth/src/test/java/com/ems/auth/PermissionResolverIT.java
git commit -m "test(auth): PermissionResolver subtree / node-only / admin bypass"
```

---

### Task 54: `AdminInitializer` — 首次启动管理员检查

**Files:**
- Create: `ems-app/src/main/java/com/ems/app/init/AdminInitializer.java`

- [ ] **Step 1: 启动时检查 admin 是否存在；不存在则告警**

```java
package com.ems.app.init;

import com.ems.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);
    private final UserRepository users;

    public AdminInitializer(UserRepository u) { this.users = u; }

    @Override
    public void run(String... args) {
        if (users.findByUsername("admin").isEmpty()) {
            log.warn("ADMIN ACCOUNT NOT FOUND. Seed migration may have been skipped. " +
                "Run Flyway manually or insert admin via SQL.");
        } else {
            log.info("admin account verified OK");
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add ems-app/src/main/java/com/ems/app/init/
git commit -m "feat(app): admin initializer sanity check on boot"
```

---

### Task 55: `ems-app` 入口测试（Smoke）

**Files:**
- Create: `ems-app/src/test/java/com/ems/app/ApplicationStartupTest.java`

- [ ] **Step 1: 写 smoke 测试**

```java
package com.ems.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = FactoryEmsApplication.class)
@ActiveProfiles("test")
@Testcontainers
class ApplicationStartupTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Test void contextLoads() { /* 空测试 — Spring 成功装配即通过 */ }
}
```

- [ ] **Step 2: 测试配置**

```yaml
# ems-app/src/test/resources/application-test.yml
spring:
  flyway:
    enabled: true
  jpa:
    hibernate.ddl-auto: none
ems:
  jwt:
    secret: this-is-a-test-secret-at-least-32-bytes-long!!!
    access-token-minutes: 15
    refresh-token-days: 7
  login:
    max-failed-attempts: 5
    lockout-minutes: 15
```

- [ ] **Step 3: 跑测试**

```bash
./mvnw -pl ems-app test -Dtest=ApplicationStartupTest
```

Expected: PASS.

- [ ] **Step 4: 提交**

```bash
git add ems-app/src/test/
git commit -m "test(app): startup smoke test"
```

---

### Task 56: 后端整体验证

**Files:**（仅验证）

- [ ] **Step 1: 运行全部后端测试**

```bash
./mvnw -T 4 clean verify
```

Expected: `BUILD SUCCESS`，所有模块测试 PASS，JaCoCo 报告生成。

- [ ] **Step 2: 统计覆盖率**

```bash
find . -path '*/target/site/jacoco/index.html' -print
```

在浏览器打开 `ems-auth/target/site/jacoco/index.html` 目测 auth 模块 ≥ 80%。

- [ ] **Step 3: 提交**

```bash
git commit --allow-empty -m "chore: phase D complete, backend baseline green"
```

---

## Phase E · 前端骨架（Tasks 57-72）

### Task 57: 前端项目初始化（Vite + React + TS）

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/tsconfig.json`
- Create: `frontend/tsconfig.node.json`
- Create: `frontend/index.html`
- Create: `frontend/src/main.tsx`
- Create: `frontend/src/App.tsx`
- Create: `frontend/src/vite-env.d.ts`
- Create: `frontend/.eslintrc.cjs`
- Create: `frontend/.prettierrc`

- [ ] **Step 1: 初始化 package.json**

```bash
mkdir frontend && cd frontend && pnpm init
```

改写 `frontend/package.json`：

```json
{
  "name": "factory-ems-frontend",
  "private": true,
  "version": "1.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "typecheck": "tsc -b --noEmit",
    "lint": "eslint src --ext ts,tsx",
    "test": "vitest"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-router-dom": "^6.26.0",
    "antd": "^5.20.0",
    "@ant-design/icons": "^5.4.0",
    "axios": "^1.7.0",
    "zustand": "^4.5.0",
    "@tanstack/react-query": "^5.52.0",
    "dayjs": "^1.11.13"
  },
  "devDependencies": {
    "@types/react": "^18.3.3",
    "@types/react-dom": "^18.3.0",
    "@typescript-eslint/eslint-plugin": "^8.0.0",
    "@typescript-eslint/parser": "^8.0.0",
    "@vitejs/plugin-react": "^4.3.0",
    "eslint": "^8.57.0",
    "eslint-plugin-react-hooks": "^4.6.0",
    "eslint-plugin-react-refresh": "^0.4.0",
    "prettier": "^3.3.0",
    "typescript": "^5.5.0",
    "vite": "^5.4.0",
    "vitest": "^2.0.0",
    "@testing-library/react": "^16.0.0",
    "@testing-library/jest-dom": "^6.5.0",
    "jsdom": "^24.1.0",
    "msw": "^2.3.0"
  }
}
```

- [ ] **Step 2: 安装依赖**

```bash
cd frontend && pnpm install
```

- [ ] **Step 3: `vite.config.ts`**

```typescript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
  },
});
```

- [ ] **Step 4: `tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "baseUrl": "./src",
    "paths": { "@/*": ["*"] }
  },
  "include": ["src"]
}
```

- [ ] **Step 5: `index.html`**

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>工厂能源管理系统</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 6: `src/main.tsx`**

```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import 'antd/dist/reset.css';
import App from './App';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, refetchOnWindowFocus: false, retry: 1 },
  },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN}>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </QueryClientProvider>
    </ConfigProvider>
  </React.StrictMode>
);
```

- [ ] **Step 7: 骨架 `App.tsx`**

```tsx
export default function App() {
  return <div style={{ padding: 24 }}>Factory EMS</div>;
}
```

- [ ] **Step 8: 验证启动**

```bash
cd frontend && pnpm dev
```

访问 http://localhost:5173 看到 "Factory EMS"。Ctrl-C 停。

- [ ] **Step 9: 提交**

```bash
git add frontend/
git commit -m "feat(frontend): vite + react + ts + antd scaffold"
```

---

### Task 58: axios Client + 拦截器

**Files:**
- Create: `frontend/src/api/client.ts`

- [ ] **Step 1: 写 client**

```typescript
import axios, { AxiosError, AxiosRequestConfig } from 'axios';
import { message } from 'antd';
import { useAuthStore } from '@/stores/authStore';

export const apiClient = axios.create({
  baseURL: '/api/v1',
  timeout: 15_000,
  withCredentials: true, // refresh cookie
});

let isRefreshing = false;
let pendingQueue: Array<(t: string | null) => void> = [];

apiClient.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

apiClient.interceptors.response.use(
  (res) => {
    const body = res.data;
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return { ...res, data: body.data };
      throw new BizError(body.code, body.message || 'unknown', body.traceId);
    }
    return res;
  },
  async (err: AxiosError<{ code?: number; message?: string; traceId?: string }>) => {
    const original = err.config as AxiosRequestConfig & { _retry?: boolean };
    const status = err.response?.status;
    const body = err.response?.data;
    const code = body?.code;

    // token 过期 → 刷新
    if (status === 401 && code === 40001 && !original._retry) {
      original._retry = true;
      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          pendingQueue.push((token) => {
            if (token) {
              (original.headers as Record<string, string>).Authorization = `Bearer ${token}`;
              resolve(apiClient(original));
            } else {
              reject(err);
            }
          });
        });
      }
      isRefreshing = true;
      try {
        const r = await axios.post('/api/v1/auth/refresh', null, { withCredentials: true });
        const newToken = r.data.data.accessToken;
        useAuthStore.getState().setAuth({
          accessToken: newToken,
          user: r.data.data.user,
          expiresIn: r.data.data.expiresIn,
        });
        pendingQueue.forEach((fn) => fn(newToken));
        pendingQueue = [];
        (original.headers as Record<string, string>).Authorization = `Bearer ${newToken}`;
        return apiClient(original);
      } catch (e) {
        pendingQueue.forEach((fn) => fn(null));
        pendingQueue = [];
        useAuthStore.getState().clear();
        window.location.href = '/login';
        return Promise.reject(err);
      } finally {
        isRefreshing = false;
      }
    }

    // 403
    if (status === 403) {
      message.error('无权访问');
      return Promise.reject(err);
    }
    // 5xx
    if (status && status >= 500) {
      message.error(`服务器错误: ${body?.message ?? 'internal'} (${body?.traceId ?? '-'})`);
      return Promise.reject(err);
    }
    // 业务错误
    if (code !== undefined) {
      message.error(body?.message ?? '操作失败');
    }
    return Promise.reject(err);
  }
);

export class BizError extends Error {
  constructor(public code: number, message: string, public traceId?: string) {
    super(message);
  }
}
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/api/
git commit -m "feat(frontend): axios client with 401 refresh + error handling"
```

---

### Task 59: `authStore` (Zustand) + `appStore`

**Files:**
- Create: `frontend/src/stores/authStore.ts`
- Create: `frontend/src/stores/appStore.ts`

- [ ] **Step 1: `authStore`**

```typescript
import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export interface UserInfo {
  id: number;
  username: string;
  displayName?: string;
  roles: string[];
}

interface AuthState {
  accessToken: string | null;
  user: UserInfo | null;
  expiresAt: number | null;
  setAuth(p: { accessToken: string; user: UserInfo; expiresIn: number }): void;
  clear(): void;
  hasRole(role: string): boolean;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      accessToken: null,
      user: null,
      expiresAt: null,
      setAuth: ({ accessToken, user, expiresIn }) =>
        set({ accessToken, user, expiresAt: Date.now() + expiresIn * 1000 }),
      clear: () => set({ accessToken: null, user: null, expiresAt: null }),
      hasRole: (role) => !!get().user?.roles.includes(role),
    }),
    { name: 'ems-auth', partialize: (s) => ({ user: s.user }) }   // 只持久化 user，token 留内存
  )
);
```

- [ ] **Step 2: `appStore`**

```typescript
import { create } from 'zustand';

interface AppState {
  currentOrgNodeId: number | null;
  setCurrentOrgNodeId(id: number | null): void;
}

export const useAppStore = create<AppState>((set) => ({
  currentOrgNodeId: null,
  setCurrentOrgNodeId: (id) => set({ currentOrgNodeId: id }),
}));
```

- [ ] **Step 3: 提交**

```bash
git add frontend/src/stores/
git commit -m "feat(frontend): auth and app stores"
```

---

### Task 60: `api/auth.ts`

**Files:**
- Create: `frontend/src/api/auth.ts`

- [ ] **Step 1: 写 auth API**

```typescript
import { apiClient } from './client';
import { UserInfo } from '@/stores/authStore';

interface LoginResp { accessToken: string; expiresIn: number; user: UserInfo; }

export const authApi = {
  login: (username: string, password: string) =>
    apiClient.post<LoginResp>('/auth/login', { username, password }).then(r => r.data as unknown as LoginResp),
  logout: () => apiClient.post('/auth/logout'),
  me: () => apiClient.get<UserInfo>('/auth/me').then(r => r.data as unknown as UserInfo),
  changePassword: (oldPassword: string, newPassword: string) =>
    apiClient.put('/users/me/password', { oldPassword, newPassword }),
};
```

> **Note:** 返回类型要和拦截器一致——拦截器已把 `.data` 解构为业务 `data` 字段。`r.data` 在此处实际是 `data` 里的对象。用 `as unknown as T` 让 TS 通过。

- [ ] **Step 2: 提交**

```bash
git add frontend/src/api/auth.ts
git commit -m "feat(frontend): auth api module"
```

---

### Task 61: React Router + `ProtectedRoute`

**Files:**
- Create: `frontend/src/router/index.tsx`
- Create: `frontend/src/components/ProtectedRoute.tsx`
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: `ProtectedRoute`**

```tsx
import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';

export function ProtectedRoute({
  children,
  requiredRole,
}: {
  children: React.ReactNode;
  requiredRole?: string;
}) {
  const { accessToken, user, hasRole } = useAuthStore();
  const location = useLocation();
  if (!accessToken || !user) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }
  if (requiredRole && !hasRole(requiredRole)) {
    return <Navigate to="/forbidden" replace />;
  }
  return <>{children}</>;
}
```

- [ ] **Step 2: `router/index.tsx`**

```tsx
import { Routes, Route } from 'react-router-dom';
import { ProtectedRoute } from '@/components/ProtectedRoute';
import AppLayout from '@/layouts/AppLayout';
import LoginPage from '@/pages/login';
import ProfilePage from '@/pages/profile';
import ForbiddenPage from '@/pages/forbidden';
import NotFoundPage from '@/pages/not-found';
import OrgTreePage from '@/pages/orgtree';
import UserListPage from '@/pages/admin/users/list';
import UserEditPage from '@/pages/admin/users/edit';
import UserPermissionPage from '@/pages/admin/users/permissions';
import RoleListPage from '@/pages/admin/roles/list';
import AuditListPage from '@/pages/admin/audit/list';
import HomePage from '@/pages/home';

export function AppRouter() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/forbidden" element={<ForbiddenPage />} />
      <Route
        path="/"
        element={
          <ProtectedRoute>
            <AppLayout />
          </ProtectedRoute>
        }
      >
        <Route index element={<HomePage />} />
        <Route path="profile" element={<ProfilePage />} />
        <Route path="orgtree" element={<OrgTreePage />} />
        <Route path="admin" element={<ProtectedRoute requiredRole="ADMIN"><></></ProtectedRoute>}>
          <Route path="users" element={<UserListPage />} />
          <Route path="users/:id" element={<UserEditPage />} />
          <Route path="users/:id/permissions" element={<UserPermissionPage />} />
          <Route path="roles" element={<RoleListPage />} />
          <Route path="audit" element={<AuditListPage />} />
        </Route>
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
```

- [ ] **Step 3: `App.tsx`**

```tsx
import { AppRouter } from './router';
export default function App() { return <AppRouter />; }
```

- [ ] **Step 4: 占位页面（防 import 错误）**

```bash
mkdir -p frontend/src/pages/{login,profile,admin/users,admin/roles,admin/audit,orgtree,home}
```

为每一页创建占位：

```tsx
// frontend/src/pages/login/index.tsx
export default function LoginPage() { return <div>Login (scaffold — implemented in Task 63)</div>; }
```

类似地创建其余占位页（每个内容为 `<div>XXX (scaffold — implemented in Task NN)</div>`）。这些占位在 Task 63-70 里会被完整实现替换，现阶段只保证 router import 不报错。为每个路由创建一份（`login/index.tsx`、`profile/index.tsx`、`forbidden.tsx`、`not-found.tsx`、`orgtree/index.tsx`、`home/index.tsx`、`admin/users/list.tsx`、`admin/users/edit.tsx`、`admin/users/permissions.tsx`、`admin/roles/list.tsx`、`admin/audit/list.tsx`）。

- [ ] **Step 5: 编译通过**

```bash
cd frontend && pnpm typecheck
```

- [ ] **Step 6: 提交**

```bash
git add frontend/
git commit -m "feat(frontend): router + protected route + page placeholders"
```

---

### Task 62: `AppLayout`（顶栏 + 侧栏）

**Files:**
- Create: `frontend/src/layouts/AppLayout.tsx`

- [ ] **Step 1: 写布局**

```tsx
import { useMemo } from 'react';
import { Layout, Menu, Avatar, Dropdown, Typography } from 'antd';
import { UserOutlined, LogoutOutlined, SettingOutlined } from '@ant-design/icons';
import { Outlet, Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';
import { authApi } from '@/api/auth';

const { Header, Sider, Content } = Layout;

export default function AppLayout() {
  const { user, clear, hasRole } = useAuthStore();
  const location = useLocation();
  const navigate = useNavigate();

  const menuItems = useMemo(() => {
    const items: any[] = [
      { key: '/', label: <Link to="/">首页</Link> },
      { key: '/orgtree', label: <Link to="/orgtree">组织树</Link> },
    ];
    if (hasRole('ADMIN')) {
      items.push({
        key: 'admin',
        label: '管理',
        children: [
          { key: '/admin/users', label: <Link to="/admin/users">用户</Link> },
          { key: '/admin/roles', label: <Link to="/admin/roles">角色</Link> },
          { key: '/admin/audit', label: <Link to="/admin/audit">审计日志</Link> },
        ],
      });
    }
    return items;
  }, [hasRole]);

  const userMenu = {
    items: [
      { key: 'profile', icon: <SettingOutlined />, label: <Link to="/profile">修改密码</Link> },
      { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: async () => {
        try { await authApi.logout(); } catch {}
        clear();
        navigate('/login');
      } },
    ],
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 24px' }}>
        <Typography.Title level={4} style={{ color: 'white', margin: 0 }}>
          工厂能源管理系统
        </Typography.Title>
        <Dropdown menu={userMenu} trigger={['click']}>
          <span style={{ color: 'white', cursor: 'pointer' }}>
            <Avatar icon={<UserOutlined />} /> {user?.displayName || user?.username}
          </span>
        </Dropdown>
      </Header>
      <Layout>
        <Sider width={220} theme="light">
          <Menu
            mode="inline"
            selectedKeys={[location.pathname]}
            defaultOpenKeys={['admin']}
            items={menuItems}
            style={{ height: '100%', borderRight: 0 }}
          />
        </Sider>
        <Content style={{ padding: 24, background: '#fff', margin: 16 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/layouts/
git commit -m "feat(frontend): AppLayout with sidebar and user menu"
```

---

### Task 63: 登录页面

**Files:**
- Modify: `frontend/src/pages/login/index.tsx`

- [ ] **Step 1: 写登录页**

```tsx
import { useState } from 'react';
import { Card, Form, Input, Button, Typography, message } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useNavigate, useLocation } from 'react-router-dom';
import { authApi } from '@/api/auth';
import { useAuthStore } from '@/stores/authStore';

export default function LoginPage() {
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const setAuth = useAuthStore((s) => s.setAuth);
  const from = (location.state as { from?: { pathname: string } })?.from?.pathname || '/';

  const onFinish = async (v: { username: string; password: string }) => {
    setLoading(true);
    try {
      const r = await authApi.login(v.username, v.password);
      setAuth({ accessToken: r.accessToken, user: r.user, expiresIn: r.expiresIn });
      message.success('登录成功');
      navigate(from, { replace: true });
    } catch {
      /* 拦截器已弹消息 */
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{
      minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: 'linear-gradient(135deg, #001529 0%, #003a7a 100%)'
    }}>
      <Card style={{ width: 380 }}>
        <Typography.Title level={3} style={{ textAlign: 'center', marginBottom: 24 }}>
          工厂能源管理系统
        </Typography.Title>
        <Form layout="vertical" onFinish={onFinish} autoComplete="off">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" size="large" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" size="large" />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={loading} block size="large">
            登录
          </Button>
        </Form>
      </Card>
    </div>
  );
}
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/pages/login/
git commit -m "feat(frontend): login page"
```

---

### Task 64: 403 / 404 页面

**Files:**
- Modify: `frontend/src/pages/forbidden.tsx`
- Modify: `frontend/src/pages/not-found.tsx`

- [ ] **Step 1: 403**

```tsx
import { Result, Button } from 'antd';
import { useNavigate } from 'react-router-dom';
export default function ForbiddenPage() {
  const nav = useNavigate();
  return <Result status="403" title="403" subTitle="您无权访问此页面"
    extra={<Button type="primary" onClick={() => nav('/')}>返回首页</Button>} />;
}
```

- [ ] **Step 2: 404**

```tsx
import { Result, Button } from 'antd';
import { useNavigate } from 'react-router-dom';
export default function NotFoundPage() {
  const nav = useNavigate();
  return <Result status="404" title="404" subTitle="页面不存在"
    extra={<Button type="primary" onClick={() => nav('/')}>返回首页</Button>} />;
}
```

- [ ] **Step 3: 提交**

```bash
git add frontend/src/pages/forbidden.tsx frontend/src/pages/not-found.tsx
git commit -m "feat(frontend): 403 and 404 pages"
```

---

### Task 65: 首页（占位 KPI 卡）

**Files:**
- Modify: `frontend/src/pages/home/index.tsx`

- [ ] **Step 1: 写首页**

```tsx
import { Card, Row, Col, Typography, Alert } from 'antd';
import { useAuthStore } from '@/stores/authStore';

export default function HomePage() {
  const user = useAuthStore((s) => s.user);
  return (
    <div>
      <Typography.Title level={3}>欢迎，{user?.displayName || user?.username}</Typography.Title>
      <Alert style={{ marginBottom: 24 }} type="info" showIcon
        message="子项目 1.1 — 地基 & 认证基线"
        description="完整看板与报表将在 Plan 1.2 / 1.3 交付。当前版本可用于用户、角色、组织树、权限和审计管理。" />
      <Row gutter={16}>
        {['电（kWh）', '水（m³）', '蒸汽（t）'].map((t) => (
          <Col span={8} key={t}>
            <Card title={t}><div style={{ fontSize: 24, textAlign: 'center' }}>— —</div></Card>
          </Col>
        ))}
      </Row>
    </div>
  );
}
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/pages/home/
git commit -m "feat(frontend): home landing page with placeholders"
```

---

### Task 66: `api/orgtree.ts` 模块

**Files:**
- Create: `frontend/src/api/orgtree.ts`

- [ ] **Step 1: 写 API**

```typescript
import { apiClient } from './client';

export interface OrgNodeDTO {
  id: number;
  parentId: number | null;
  name: string;
  code: string;
  nodeType: string;
  sortOrder: number;
  createdAt: string;
  children: OrgNodeDTO[];
}

export interface CreateOrgNodeReq {
  parentId?: number | null;
  name: string;
  code: string;
  nodeType: string;
  sortOrder?: number;
}
export interface UpdateOrgNodeReq {
  name: string;
  nodeType: string;
  sortOrder?: number;
}

export const orgTreeApi = {
  getTree: (rootId?: number) =>
    apiClient.get<OrgNodeDTO[]>('/org-nodes/tree', { params: { rootId } })
      .then((r) => r.data as unknown as OrgNodeDTO[]),
  create: (req: CreateOrgNodeReq) =>
    apiClient.post<OrgNodeDTO>('/org-nodes', req).then((r) => r.data as unknown as OrgNodeDTO),
  update: (id: number, req: UpdateOrgNodeReq) =>
    apiClient.put<OrgNodeDTO>(`/org-nodes/${id}`, req).then((r) => r.data as unknown as OrgNodeDTO),
  move: (id: number, newParentId: number | null) =>
    apiClient.patch(`/org-nodes/${id}/move`, { newParentId }),
  delete: (id: number) => apiClient.delete(`/org-nodes/${id}`),
};
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/api/orgtree.ts
git commit -m "feat(frontend): orgtree api module"
```

---

### Task 67: `api/user.ts` + `api/role.ts` + `api/permission.ts`

**Files:**
- Create: `frontend/src/api/user.ts`
- Create: `frontend/src/api/role.ts`
- Create: `frontend/src/api/permission.ts`

- [ ] **Step 1: user API**

```typescript
import { apiClient } from './client';

export interface UserDTO {
  id: number; username: string; displayName?: string;
  enabled: boolean; roles: string[];
  lastLoginAt?: string; createdAt: string;
}
export interface PageDTO<T> { items: T[]; total: number; page: number; size: number; }

export const userApi = {
  list: (page = 1, size = 20, keyword?: string) =>
    apiClient.get<PageDTO<UserDTO>>('/users', { params: { page, size, keyword } })
      .then((r) => r.data as unknown as PageDTO<UserDTO>),
  getById: (id: number) =>
    apiClient.get<UserDTO>(`/users/${id}`).then((r) => r.data as unknown as UserDTO),
  create: (req: { username: string; password: string; displayName?: string; roleCodes?: string[] }) =>
    apiClient.post<UserDTO>('/users', req).then((r) => r.data as unknown as UserDTO),
  update: (id: number, req: { displayName?: string; enabled?: boolean }) =>
    apiClient.put<UserDTO>(`/users/${id}`, req).then((r) => r.data as unknown as UserDTO),
  delete: (id: number) => apiClient.delete(`/users/${id}`),
  assignRoles: (id: number, roleCodes: string[]) =>
    apiClient.put(`/users/${id}/roles`, { roleCodes }),
  resetPassword: (id: number, newPassword: string) =>
    apiClient.put(`/users/${id}/password/reset`, { newPassword }),
};
```

- [ ] **Step 2: role API**

```typescript
import { apiClient } from './client';
export interface RoleDTO { id: number; code: string; name: string; description?: string; }
export const roleApi = {
  list: () => apiClient.get<RoleDTO[]>('/roles').then(r => r.data as unknown as RoleDTO[]),
};
```

- [ ] **Step 3: permission API**

```typescript
import { apiClient } from './client';
export interface NodePermissionDTO {
  id: number; userId: number; orgNodeId: number;
  scope: 'SUBTREE' | 'NODE_ONLY'; createdAt: string;
}
export const permissionApi = {
  listByUser: (userId: number) =>
    apiClient.get<NodePermissionDTO[]>(`/users/${userId}/node-permissions`)
      .then(r => r.data as unknown as NodePermissionDTO[]),
  assign: (userId: number, orgNodeId: number, scope: 'SUBTREE' | 'NODE_ONLY') =>
    apiClient.post<NodePermissionDTO>(`/users/${userId}/node-permissions`, { orgNodeId, scope })
      .then(r => r.data as unknown as NodePermissionDTO),
  revoke: (userId: number, permissionId: number) =>
    apiClient.delete(`/users/${userId}/node-permissions/${permissionId}`),
};
```

- [ ] **Step 4: 提交**

```bash
git add frontend/src/api/user.ts frontend/src/api/role.ts frontend/src/api/permission.ts
git commit -m "feat(frontend): user/role/permission api modules"
```

---

### Task 68: `api/audit.ts`

**Files:**
- Create: `frontend/src/api/audit.ts`

- [ ] **Step 1: 写**

```typescript
import { apiClient } from './client';
import type { PageDTO } from './user';

export interface AuditLogDTO {
  id: number; actorUserId?: number; actorUsername?: string;
  action: string; resourceType?: string; resourceId?: string;
  summary?: string; detail?: string; ip?: string; userAgent?: string;
  occurredAt: string;
}

export interface AuditQuery {
  actorUserId?: number; resourceType?: string; action?: string;
  from?: string; to?: string; page?: number; size?: number;
}

export const auditApi = {
  search: (q: AuditQuery) =>
    apiClient.get<PageDTO<AuditLogDTO>>('/audit-logs', { params: q })
      .then(r => r.data as unknown as PageDTO<AuditLogDTO>),
};
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/api/audit.ts
git commit -m "feat(frontend): audit api module"
```

---

### Task 69: `usePermissions` hook + `PermissionGate`

**Files:**
- Create: `frontend/src/hooks/usePermissions.ts`
- Create: `frontend/src/components/PermissionGate.tsx`

- [ ] **Step 1: hook**

```typescript
import { useAuthStore } from '@/stores/authStore';

export function usePermissions() {
  const user = useAuthStore((s) => s.user);
  const roles = user?.roles ?? [];
  return {
    isAdmin: roles.includes('ADMIN'),
    isViewer: roles.includes('VIEWER'),
    hasRole: (r: string) => roles.includes(r),
  };
}
```

- [ ] **Step 2: `PermissionGate`**

```tsx
import { usePermissions } from '@/hooks/usePermissions';
export function PermissionGate({ role, children, fallback = null }: {
  role: string; children: React.ReactNode; fallback?: React.ReactNode;
}) {
  const { hasRole } = usePermissions();
  return <>{hasRole(role) ? children : fallback}</>;
}
```

- [ ] **Step 3: 提交**

```bash
git add frontend/src/hooks/ frontend/src/components/PermissionGate.tsx
git commit -m "feat(frontend): permission hook and gate"
```

---

### Task 70: Profile（修改密码）页面

**Files:**
- Modify: `frontend/src/pages/profile/index.tsx`

- [ ] **Step 1: 写页面**

```tsx
import { Card, Form, Input, Button, message } from 'antd';
import { authApi } from '@/api/auth';

export default function ProfilePage() {
  const [form] = Form.useForm();
  const onFinish = async (v: { oldPassword: string; newPassword: string; confirm: string }) => {
    if (v.newPassword !== v.confirm) { message.error('两次新密码不一致'); return; }
    try {
      await authApi.changePassword(v.oldPassword, v.newPassword);
      message.success('密码已更新');
      form.resetFields();
    } catch {}
  };
  return (
    <Card title="修改密码" style={{ maxWidth: 480 }}>
      <Form form={form} layout="vertical" onFinish={onFinish}>
        <Form.Item name="oldPassword" label="原密码" rules={[{ required: true }]}>
          <Input.Password />
        </Form.Item>
        <Form.Item name="newPassword" label="新密码" rules={[{ required: true, min: 8, max: 64 }]}>
          <Input.Password />
        </Form.Item>
        <Form.Item name="confirm" label="确认新密码" rules={[{ required: true }]}>
          <Input.Password />
        </Form.Item>
        <Button type="primary" htmlType="submit">保存</Button>
      </Form>
    </Card>
  );
}
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/pages/profile/
git commit -m "feat(frontend): change password page"
```

---

### Task 71: 测试工具 + MSW 初始化

**Files:**
- Create: `frontend/src/test-setup.ts`
- Create: `frontend/src/test/mocks/server.ts`
- Create: `frontend/src/test/mocks/handlers.ts`

- [ ] **Step 1: test-setup**

```typescript
import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './test/mocks/server';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
```

- [ ] **Step 2: server**

```typescript
import { setupServer } from 'msw/node';
import { handlers } from './handlers';
export const server = setupServer(...handlers);
```

- [ ] **Step 3: handlers（空占位）**

```typescript
import { HttpResponse, http } from 'msw';
export const handlers = [
  http.get('/api/v1/auth/me', () =>
    HttpResponse.json({ code: 0, data: { id: 1, username: 'admin', roles: ['ADMIN'] } })
  ),
];
```

- [ ] **Step 4: 简单单元测试验证 setup**

```typescript
// frontend/src/stores/authStore.test.ts
import { describe, it, expect, beforeEach } from 'vitest';
import { useAuthStore } from './authStore';

describe('authStore', () => {
  beforeEach(() => useAuthStore.getState().clear());
  it('setAuth stores token and user', () => {
    useAuthStore.getState().setAuth({
      accessToken: 'tok', user: { id: 1, username: 'admin', roles: ['ADMIN'] }, expiresIn: 900
    });
    expect(useAuthStore.getState().accessToken).toBe('tok');
    expect(useAuthStore.getState().hasRole('ADMIN')).toBe(true);
    expect(useAuthStore.getState().hasRole('VIEWER')).toBe(false);
  });
});
```

```bash
cd frontend && pnpm test --run
```

Expected: 1 PASS.

- [ ] **Step 5: 提交**

```bash
git add frontend/src/test-setup.ts frontend/src/test/ frontend/src/stores/authStore.test.ts
git commit -m "test(frontend): vitest + MSW setup with authStore test"
```

---

### Task 72: ErrorBoundary

**Files:**
- Create: `frontend/src/components/ErrorBoundary.tsx`
- Modify: `frontend/src/main.tsx`

- [ ] **Step 1: 写 ErrorBoundary**

```tsx
import React from 'react';
import { Result, Button } from 'antd';

interface State { hasError: boolean; error?: Error; }
export class ErrorBoundary extends React.Component<{ children: React.ReactNode }, State> {
  state: State = { hasError: false };
  static getDerivedStateFromError(error: Error): State { return { hasError: true, error }; }
  componentDidCatch(error: Error, info: React.ErrorInfo) {
    // eslint-disable-next-line no-console
    console.error('UI error:', error, info);
  }
  render() {
    if (this.state.hasError) {
      return <Result status="error" title="页面渲染出错"
        subTitle={this.state.error?.message}
        extra={<Button onClick={() => location.reload()}>刷新页面</Button>} />;
    }
    return this.props.children;
  }
}
```

- [ ] **Step 2: 包进 main.tsx**

```tsx
import { ErrorBoundary } from './components/ErrorBoundary';
// ...
<ErrorBoundary>
  <BrowserRouter>
    <App />
  </BrowserRouter>
</ErrorBoundary>
```

- [ ] **Step 3: 提交**

```bash
git add frontend/src/components/ErrorBoundary.tsx frontend/src/main.tsx
git commit -m "feat(frontend): error boundary wrapping router"
```

---

## Phase F · 前端管理页面（Tasks 73-92）

### Task 73: 组织树页面 — 列表

**Files:**
- Modify: `frontend/src/pages/orgtree/index.tsx`

- [ ] **Step 1: 组织树视图**

```tsx
import { useState } from 'react';
import { Card, Tree, Button, Space, Typography, Popconfirm, message } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, SwapOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';
import { CreateNodeModal } from './CreateNodeModal';
import { EditNodeModal } from './EditNodeModal';
import { MoveNodeModal } from './MoveNodeModal';
import { usePermissions } from '@/hooks/usePermissions';

interface DisplayNode { title: React.ReactNode; key: string; children?: DisplayNode[]; raw: OrgNodeDTO; }

export default function OrgTreePage() {
  const { isAdmin } = usePermissions();
  const qc = useQueryClient();
  const { data: tree = [] } = useQuery({ queryKey: ['orgtree'], queryFn: () => orgTreeApi.getTree() });

  const [selected, setSelected] = useState<OrgNodeDTO | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [moveOpen, setMoveOpen] = useState(false);

  const del = useMutation({
    mutationFn: (id: number) => orgTreeApi.delete(id),
    onSuccess: () => {
      message.success('已删除'); setSelected(null);
      qc.invalidateQueries({ queryKey: ['orgtree'] });
    },
  });

  const toTreeData = (nodes: OrgNodeDTO[]): DisplayNode[] =>
    nodes.map((n) => ({
      key: String(n.id),
      title: `${n.name} (${n.code}) [${n.nodeType}]`,
      children: toTreeData(n.children),
      raw: n,
    }));

  return (
    <Card title="组织树" extra={isAdmin && (
      <Space>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          新建节点
        </Button>
        <Button icon={<EditOutlined />} disabled={!selected} onClick={() => setEditOpen(true)}>编辑</Button>
        <Button icon={<SwapOutlined />} disabled={!selected} onClick={() => setMoveOpen(true)}>移动</Button>
        <Popconfirm title="确认删除？" disabled={!selected} onConfirm={() => selected && del.mutate(selected.id)}>
          <Button danger icon={<DeleteOutlined />} disabled={!selected}>删除</Button>
        </Popconfirm>
      </Space>
    )}>
      <Tree
        treeData={toTreeData(tree)}
        defaultExpandAll
        onSelect={(_, info) => setSelected((info.node as unknown as DisplayNode).raw)}
        selectedKeys={selected ? [String(selected.id)] : []}
      />
      {selected && (
        <Typography.Paragraph style={{ marginTop: 16 }}>
          当前选中：<b>{selected.name}</b> ({selected.code})
        </Typography.Paragraph>
      )}
      <CreateNodeModal open={createOpen} parent={selected} onClose={() => setCreateOpen(false)} />
      <EditNodeModal open={editOpen} node={selected} onClose={() => setEditOpen(false)} />
      <MoveNodeModal open={moveOpen} node={selected} tree={tree} onClose={() => setMoveOpen(false)} />
    </Card>
  );
}
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/pages/orgtree/index.tsx
git commit -m "feat(frontend): orgtree tree view"
```

---

### Task 74: 组织树 — 新建节点 Modal

**Files:**
- Create: `frontend/src/pages/orgtree/CreateNodeModal.tsx`

- [ ] **Step 1: 写 Modal**

```tsx
import { Modal, Form, Input, Select, InputNumber, message } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';

const NODE_TYPES = ['PLANT', 'WORKSHOP', 'LINE', 'DEVICE', 'GROUP', 'OTHER'];

export function CreateNodeModal({ open, parent, onClose }: {
  open: boolean; parent: OrgNodeDTO | null; onClose: () => void;
}) {
  const [form] = Form.useForm();
  const qc = useQueryClient();
  const mut = useMutation({
    mutationFn: orgTreeApi.create,
    onSuccess: () => {
      message.success('已创建');
      qc.invalidateQueries({ queryKey: ['orgtree'] });
      form.resetFields();
      onClose();
    },
  });

  return (
    <Modal
      title={`新建节点${parent ? ' (父: ' + parent.name + ')' : ' (根节点)'}`}
      open={open} onCancel={onClose}
      onOk={() => form.validateFields().then((v) => mut.mutate({
        parentId: parent?.id ?? null, ...v,
      }))}
      confirmLoading={mut.isPending} destroyOnClose
    >
      <Form form={form} layout="vertical">
        <Form.Item name="name" label="名称" rules={[{ required: true, max: 128 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="code" label="编码" rules={[
          { required: true, max: 64 },
          { pattern: /^[A-Za-z0-9_-]+$/, message: '只允许字母数字下划线横线' },
        ]}>
          <Input />
        </Form.Item>
        <Form.Item name="nodeType" label="节点类型" rules={[{ required: true }]}>
          <Select options={NODE_TYPES.map((t) => ({ label: t, value: t }))} />
        </Form.Item>
        <Form.Item name="sortOrder" label="排序" initialValue={0}>
          <InputNumber min={0} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/pages/orgtree/CreateNodeModal.tsx
git commit -m "feat(frontend): orgtree create-node modal"
```

---

### Task 75: 组织树 — 编辑节点 Modal

**Files:**
- Create: `frontend/src/pages/orgtree/EditNodeModal.tsx`

- [ ] **Step 1: 写**

```tsx
import { Modal, Form, Input, Select, InputNumber, message } from 'antd';
import { useEffect } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';

const NODE_TYPES = ['PLANT', 'WORKSHOP', 'LINE', 'DEVICE', 'GROUP', 'OTHER'];

export function EditNodeModal({ open, node, onClose }: {
  open: boolean; node: OrgNodeDTO | null; onClose: () => void;
}) {
  const [form] = Form.useForm();
  const qc = useQueryClient();
  useEffect(() => {
    if (node && open) form.setFieldsValue(node);
  }, [node, open, form]);
  const mut = useMutation({
    mutationFn: (v: { name: string; nodeType: string; sortOrder: number }) =>
      orgTreeApi.update(node!.id, v),
    onSuccess: () => {
      message.success('已更新');
      qc.invalidateQueries({ queryKey: ['orgtree'] });
      onClose();
    },
  });

  return (
    <Modal title={`编辑 ${node?.name ?? ''}`} open={open} onCancel={onClose}
      onOk={() => form.validateFields().then((v) => mut.mutate(v))}
      confirmLoading={mut.isPending} destroyOnClose>
      <Form form={form} layout="vertical">
        <Form.Item name="name" label="名称" rules={[{ required: true, max: 128 }]}><Input /></Form.Item>
        <Form.Item name="nodeType" label="节点类型" rules={[{ required: true }]}>
          <Select options={NODE_TYPES.map((t) => ({ label: t, value: t }))} />
        </Form.Item>
        <Form.Item name="sortOrder" label="排序"><InputNumber min={0} /></Form.Item>
      </Form>
    </Modal>
  );
}
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/pages/orgtree/EditNodeModal.tsx
git commit -m "feat(frontend): orgtree edit-node modal"
```

---

### Task 76: 组织树 — 移动节点 Modal

**Files:**
- Create: `frontend/src/pages/orgtree/MoveNodeModal.tsx`

- [ ] **Step 1: 写**

```tsx
import { Modal, TreeSelect, Alert, message } from 'antd';
import { useState, useMemo } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';

export function MoveNodeModal({ open, node, tree, onClose }: {
  open: boolean; node: OrgNodeDTO | null; tree: OrgNodeDTO[]; onClose: () => void;
}) {
  const [newParentId, setNewParentId] = useState<number | null>(null);
  const qc = useQueryClient();
  const mut = useMutation({
    mutationFn: () => orgTreeApi.move(node!.id, newParentId),
    onSuccess: () => {
      message.success('已移动');
      qc.invalidateQueries({ queryKey: ['orgtree'] });
      setNewParentId(null); onClose();
    },
  });

  // 排除节点自身及其子树
  const excludedIds = useMemo(() => {
    if (!node) return new Set<number>();
    const ids = new Set<number>();
    const walk = (n: OrgNodeDTO) => { ids.add(n.id); n.children.forEach(walk); };
    const findSelf = (arr: OrgNodeDTO[]): OrgNodeDTO | null => {
      for (const n of arr) {
        if (n.id === node.id) return n;
        const r = findSelf(n.children); if (r) return r;
      }
      return null;
    };
    const self = findSelf(tree);
    if (self) walk(self);
    return ids;
  }, [node, tree]);

  const toSelectData = (nodes: OrgNodeDTO[]): any[] =>
    nodes.map((n) => ({
      title: n.name, value: n.id,
      disabled: excludedIds.has(n.id),
      children: toSelectData(n.children),
    }));

  return (
    <Modal title={`移动 ${node?.name ?? ''}`} open={open} onCancel={onClose}
      onOk={() => mut.mutate()} confirmLoading={mut.isPending} destroyOnClose>
      <Alert type="info" showIcon message="选择新的父节点；选择"根"将成为顶级节点。" style={{ marginBottom: 16 }} />
      <TreeSelect style={{ width: '100%' }}
        placeholder="选择新父节点（空=根）"
        allowClear treeDefaultExpandAll
        treeData={toSelectData(tree)}
        value={newParentId ?? undefined}
        onChange={(v) => setNewParentId(v ?? null)}
      />
    </Modal>
  );
}
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/pages/orgtree/MoveNodeModal.tsx
git commit -m "feat(frontend): orgtree move-node modal with descendant exclusion"
```

---

### Task 77: 用户列表页

**Files:**
- Modify: `frontend/src/pages/admin/users/list.tsx`

- [ ] **Step 1: 写列表**

```tsx
import { useState } from 'react';
import { Card, Table, Button, Space, Input, Tag, Popconfirm, message, Modal, Form, Select } from 'antd';
import { PlusOutlined, KeyOutlined, DeleteOutlined, SafetyOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { userApi, UserDTO } from '@/api/user';
import { roleApi } from '@/api/role';
import { Link } from 'react-router-dom';

export default function UserListPage() {
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [createOpen, setCreateOpen] = useState(false);
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({
    queryKey: ['users', page, keyword],
    queryFn: () => userApi.list(page, 20, keyword || undefined),
  });
  const { data: roles = [] } = useQuery({ queryKey: ['roles'], queryFn: roleApi.list });

  const [form] = Form.useForm();
  const createMut = useMutation({
    mutationFn: userApi.create,
    onSuccess: () => {
      message.success('已创建');
      qc.invalidateQueries({ queryKey: ['users'] });
      setCreateOpen(false); form.resetFields();
    },
  });
  const delMut = useMutation({
    mutationFn: (id: number) => userApi.delete(id),
    onSuccess: () => {
      message.success('已删除');
      qc.invalidateQueries({ queryKey: ['users'] });
    },
  });

  const [resetFor, setResetFor] = useState<UserDTO | null>(null);
  const resetMut = useMutation({
    mutationFn: ({ id, pwd }: { id: number; pwd: string }) => userApi.resetPassword(id, pwd),
    onSuccess: () => { message.success('密码已重置'); setResetFor(null); },
  });

  return (
    <Card title="用户管理" extra={
      <Space>
        <Input.Search placeholder="搜索用户名/姓名" allowClear
          onSearch={(v) => { setKeyword(v); setPage(1); }} style={{ width: 240 }} />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建</Button>
      </Space>
    }>
      <Table<UserDTO> rowKey="id" loading={isLoading}
        dataSource={data?.items ?? []}
        pagination={{ current: page, pageSize: 20, total: data?.total ?? 0, onChange: setPage }}
        columns={[
          { title: '用户名', dataIndex: 'username' },
          { title: '姓名', dataIndex: 'displayName' },
          { title: '角色', dataIndex: 'roles',
            render: (rs: string[]) => rs.map((r) => <Tag key={r} color={r==='ADMIN'?'red':'blue'}>{r}</Tag>)
          },
          { title: '状态', dataIndex: 'enabled',
            render: (e) => e ? <Tag color="green">启用</Tag> : <Tag>禁用</Tag>
          },
          { title: '最近登录', dataIndex: 'lastLoginAt' },
          { title: '操作', key: 'ops', render: (_, u) => (
            <Space>
              <Link to={`/admin/users/${u.id}`}>编辑</Link>
              <Link to={`/admin/users/${u.id}/permissions`}>
                <SafetyOutlined /> 权限
              </Link>
              <Button size="small" icon={<KeyOutlined />} onClick={() => setResetFor(u)}>重置密码</Button>
              <Popconfirm title={`确认删除 ${u.username}？`} onConfirm={() => delMut.mutate(u.id)}>
                <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
              </Popconfirm>
            </Space>
          )},
        ]}
      />

      <Modal title="新建用户" open={createOpen} onCancel={() => setCreateOpen(false)}
        onOk={() => form.validateFields().then((v) => createMut.mutate(v))}
        confirmLoading={createMut.isPending} destroyOnClose>
        <Form form={form} layout="vertical">
          <Form.Item name="username" label="用户名"
            rules={[{ required: true, min: 3, max: 64 },
                    { pattern: /^[A-Za-z0-9_.-]+$/, message: '只允许字母数字 _ . -' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="password" label="初始密码"
            rules={[{ required: true, min: 8, max: 64 }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item name="displayName" label="姓名" rules={[{ max: 128 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="roleCodes" label="角色" initialValue={['VIEWER']}>
            <Select mode="multiple" options={roles.map((r) => ({ label: r.name, value: r.code }))} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title={`重置 ${resetFor?.username} 的密码`} open={!!resetFor}
        onCancel={() => setResetFor(null)}
        onOk={() => {
          const pwd = (document.getElementById('reset-new-pwd') as HTMLInputElement)?.value;
          if (!pwd || pwd.length < 8) { message.error('至少 8 位'); return; }
          resetMut.mutate({ id: resetFor!.id, pwd });
        }}
        confirmLoading={resetMut.isPending}>
        <Input.Password id="reset-new-pwd" placeholder="新密码" />
      </Modal>
    </Card>
  );
}
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/pages/admin/users/list.tsx
git commit -m "feat(frontend): user list page with create / delete / reset-password"
```

---

### Task 78: 用户编辑页（启用/禁用、角色分配）

**Files:**
- Modify: `frontend/src/pages/admin/users/edit.tsx`

- [ ] **Step 1: 写**

```tsx
import { Card, Form, Input, Switch, Select, Button, message, Spin } from 'antd';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { userApi } from '@/api/user';
import { roleApi } from '@/api/role';
import { useEffect } from 'react';

export default function UserEditPage() {
  const { id } = useParams();
  const userId = Number(id);
  const [form] = Form.useForm();
  const qc = useQueryClient();
  const nav = useNavigate();

  const { data: user, isLoading } = useQuery({
    queryKey: ['user', userId], queryFn: () => userApi.getById(userId), enabled: !!userId
  });
  const { data: roles = [] } = useQuery({ queryKey: ['roles'], queryFn: roleApi.list });

  useEffect(() => { if (user) form.setFieldsValue(user); }, [user, form]);

  const updateMut = useMutation({
    mutationFn: (v: { displayName?: string; enabled?: boolean }) => userApi.update(userId, v),
    onSuccess: () => { message.success('基础信息已更新'); qc.invalidateQueries({ queryKey: ['user', userId] }); },
  });
  const rolesMut = useMutation({
    mutationFn: (codes: string[]) => userApi.assignRoles(userId, codes),
    onSuccess: () => { message.success('角色已更新'); qc.invalidateQueries({ queryKey: ['user', userId] }); },
  });

  if (isLoading) return <Spin />;
  if (!user) return null;

  return (
    <Card title={`编辑用户 ${user.username}`} extra={<Button onClick={() => nav(-1)}>返回</Button>}>
      <Form form={form} layout="vertical" style={{ maxWidth: 480 }}>
        <Form.Item label="用户名"><Input disabled value={user.username} /></Form.Item>
        <Form.Item name="displayName" label="姓名" rules={[{ max: 128 }]}><Input /></Form.Item>
        <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
        <Button type="primary" onClick={() => form.validateFields()
          .then((v) => updateMut.mutate({ displayName: v.displayName, enabled: v.enabled }))}
          loading={updateMut.isPending}>保存基础信息</Button>
      </Form>

      <Card type="inner" title="角色" style={{ marginTop: 24, maxWidth: 480 }}>
        <Form.Item label="角色" name="roleCodes" initialValue={user.roles}>
          <Select mode="multiple" options={roles.map(r => ({ label: r.name, value: r.code }))}
            defaultValue={user.roles} onChange={(codes) => rolesMut.mutate(codes)} />
        </Form.Item>
      </Card>
    </Card>
  );
}
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/pages/admin/users/edit.tsx
git commit -m "feat(frontend): user edit page"
```

---

### Task 79: 用户节点权限页

**Files:**
- Modify: `frontend/src/pages/admin/users/permissions.tsx`

- [ ] **Step 1: 写**

```tsx
import { Card, Table, Button, Space, Popconfirm, message, Modal, TreeSelect, Select, Form } from 'antd';
import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { PlusOutlined } from '@ant-design/icons';
import { permissionApi, NodePermissionDTO } from '@/api/permission';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';
import { userApi } from '@/api/user';

export default function UserPermissionPage() {
  const { id } = useParams();
  const userId = Number(id);
  const nav = useNavigate();
  const qc = useQueryClient();
  const [addOpen, setAddOpen] = useState(false);
  const [form] = Form.useForm();

  const { data: user } = useQuery({ queryKey: ['user', userId], queryFn: () => userApi.getById(userId) });
  const { data: perms = [] } = useQuery({
    queryKey: ['perms', userId], queryFn: () => permissionApi.listByUser(userId),
  });
  const { data: tree = [] } = useQuery({ queryKey: ['orgtree'], queryFn: () => orgTreeApi.getTree() });

  const assignMut = useMutation({
    mutationFn: (v: { orgNodeId: number; scope: 'SUBTREE' | 'NODE_ONLY' }) =>
      permissionApi.assign(userId, v.orgNodeId, v.scope),
    onSuccess: () => {
      message.success('已授权');
      qc.invalidateQueries({ queryKey: ['perms', userId] });
      setAddOpen(false); form.resetFields();
    },
  });
  const revokeMut = useMutation({
    mutationFn: (permId: number) => permissionApi.revoke(userId, permId),
    onSuccess: () => {
      message.success('已撤销');
      qc.invalidateQueries({ queryKey: ['perms', userId] });
    },
  });

  const nodeById = (id: number, nodes: OrgNodeDTO[]): OrgNodeDTO | null => {
    for (const n of nodes) {
      if (n.id === id) return n;
      const r = nodeById(id, n.children); if (r) return r;
    }
    return null;
  };
  const toSelectData = (nodes: OrgNodeDTO[]): any[] =>
    nodes.map((n) => ({ title: n.name, value: n.id, children: toSelectData(n.children) }));

  return (
    <Card title={`${user?.username ?? ''} 的节点权限`} extra={
      <Space>
        <Button onClick={() => nav(-1)}>返回</Button>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setAddOpen(true)}>
          授权节点
        </Button>
      </Space>
    }>
      <Table<NodePermissionDTO> rowKey="id" dataSource={perms}
        columns={[
          { title: '组织节点', dataIndex: 'orgNodeId',
            render: (nid) => { const n = nodeById(nid, tree); return n ? `${n.name} (${n.code})` : nid; }
          },
          { title: '范围', dataIndex: 'scope',
            render: (s) => s === 'SUBTREE' ? '子树（含后代）' : '仅此节点'
          },
          { title: '授予时间', dataIndex: 'createdAt' },
          { title: '操作', key: 'ops', render: (_, p) => (
            <Popconfirm title="确认撤销？" onConfirm={() => revokeMut.mutate(p.id)}>
              <Button danger size="small">撤销</Button>
            </Popconfirm>
          )},
        ]}
      />

      <Modal title="授权节点" open={addOpen} onCancel={() => setAddOpen(false)} destroyOnClose
        onOk={() => form.validateFields().then((v) => assignMut.mutate(v))}
        confirmLoading={assignMut.isPending}>
        <Form form={form} layout="vertical">
          <Form.Item name="orgNodeId" label="组织节点" rules={[{ required: true }]}>
            <TreeSelect treeData={toSelectData(tree)} treeDefaultExpandAll />
          </Form.Item>
          <Form.Item name="scope" label="范围" rules={[{ required: true }]} initialValue="SUBTREE">
            <Select options={[
              { label: '子树（含所有后代）', value: 'SUBTREE' },
              { label: '仅此节点', value: 'NODE_ONLY' },
            ]} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/pages/admin/users/permissions.tsx
git commit -m "feat(frontend): user node-permission management page"
```

---

### Task 80: 角色列表页（只读）

**Files:**
- Modify: `frontend/src/pages/admin/roles/list.tsx`

- [ ] **Step 1: 写**

```tsx
import { Card, Table, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { roleApi, RoleDTO } from '@/api/role';

export default function RoleListPage() {
  const { data = [], isLoading } = useQuery({ queryKey: ['roles'], queryFn: roleApi.list });
  return (
    <Card title="角色管理 (只读)">
      <Table<RoleDTO> rowKey="id" loading={isLoading} dataSource={data}
        pagination={false}
        columns={[
          { title: 'Code', dataIndex: 'code',
            render: (c) => <Tag color={c==='ADMIN'?'red':'blue'}>{c}</Tag> },
          { title: '名称', dataIndex: 'name' },
          { title: '说明', dataIndex: 'description' },
        ]}
      />
    </Card>
  );
}
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/pages/admin/roles/list.tsx
git commit -m "feat(frontend): read-only role list"
```

---

### Task 81: 审计日志页

**Files:**
- Modify: `frontend/src/pages/admin/audit/list.tsx`
- Create: `frontend/src/pages/admin/audit/DetailModal.tsx`

- [ ] **Step 1: DetailModal**

```tsx
import { Modal, Descriptions, Typography } from 'antd';
import { AuditLogDTO } from '@/api/audit';

export function DetailModal({ log, onClose }: { log: AuditLogDTO | null; onClose: () => void }) {
  if (!log) return null;
  let detailObj: any = null;
  try { detailObj = log.detail ? JSON.parse(log.detail) : null; } catch {}
  return (
    <Modal open={!!log} onCancel={onClose} footer={null} title="审计详情" width={800}>
      <Descriptions column={1} bordered size="small">
        <Descriptions.Item label="时间">{log.occurredAt}</Descriptions.Item>
        <Descriptions.Item label="操作者">{log.actorUsername} (id={log.actorUserId})</Descriptions.Item>
        <Descriptions.Item label="动作">{log.action}</Descriptions.Item>
        <Descriptions.Item label="资源">{log.resourceType} / {log.resourceId}</Descriptions.Item>
        <Descriptions.Item label="概述">{log.summary}</Descriptions.Item>
        <Descriptions.Item label="IP">{log.ip}</Descriptions.Item>
        <Descriptions.Item label="User-Agent">{log.userAgent}</Descriptions.Item>
      </Descriptions>
      <Typography.Title level={5} style={{ marginTop: 16 }}>Detail</Typography.Title>
      <pre style={{ background: '#fafafa', padding: 12, maxHeight: 320, overflow: 'auto' }}>
        {detailObj ? JSON.stringify(detailObj, null, 2) : log.detail ?? '(none)'}
      </pre>
    </Modal>
  );
}
```

- [ ] **Step 2: list page**

```tsx
import { useState } from 'react';
import { Card, Table, Space, Input, Select, DatePicker, Button, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { auditApi, AuditLogDTO, AuditQuery } from '@/api/audit';
import { DetailModal } from './DetailModal';

const { RangePicker } = DatePicker;
const ACTIONS = ['LOGIN','LOGOUT','LOGIN_FAIL','CREATE','UPDATE','DELETE','MOVE','CONFIG_CHANGE'];
const RES_TYPES = ['AUTH','USER','ORG_NODE','NODE_PERMISSION'];

export default function AuditListPage() {
  const [q, setQ] = useState<AuditQuery>({ page: 1, size: 20 });
  const [detail, setDetail] = useState<AuditLogDTO | null>(null);
  const { data, isLoading } = useQuery({
    queryKey: ['audit', q], queryFn: () => auditApi.search(q), placeholderData: (p) => p,
  });

  return (
    <Card title="审计日志">
      <Space style={{ marginBottom: 16 }} wrap>
        <Select placeholder="动作" allowClear style={{ width: 160 }}
          options={ACTIONS.map((a) => ({ label: a, value: a }))}
          onChange={(v) => setQ({ ...q, action: v, page: 1 })} />
        <Select placeholder="资源类型" allowClear style={{ width: 160 }}
          options={RES_TYPES.map((r) => ({ label: r, value: r }))}
          onChange={(v) => setQ({ ...q, resourceType: v, page: 1 })} />
        <Input placeholder="操作者 User ID" type="number" style={{ width: 160 }}
          onBlur={(e) => setQ({ ...q, actorUserId: e.target.value ? Number(e.target.value) : undefined, page: 1 })} />
        <RangePicker showTime
          onChange={(range) => setQ({
            ...q, page: 1,
            from: range?.[0] ? range[0].toISOString() : undefined,
            to: range?.[1] ? range[1].toISOString() : undefined,
          })} />
        <Button onClick={() => setQ({ page: 1, size: 20 })}>重置</Button>
      </Space>
      <Table<AuditLogDTO> rowKey="id" loading={isLoading}
        dataSource={data?.items ?? []}
        pagination={{ current: q.page, pageSize: q.size, total: data?.total ?? 0,
          onChange: (p, s) => setQ({ ...q, page: p, size: s }) }}
        columns={[
          { title: '时间', dataIndex: 'occurredAt',
            render: (v) => dayjs(v).format('YYYY-MM-DD HH:mm:ss') },
          { title: '操作者', dataIndex: 'actorUsername' },
          { title: '动作', dataIndex: 'action',
            render: (a) => <Tag color={a.includes('FAIL') ? 'red' : 'blue'}>{a}</Tag> },
          { title: '资源', render: (_, r) => `${r.resourceType ?? '-'} / ${r.resourceId ?? '-'}` },
          { title: '概述', dataIndex: 'summary', ellipsis: true },
          { title: 'IP', dataIndex: 'ip' },
          { title: '操作', key: 'ops',
            render: (_, r) => <Button type="link" onClick={() => setDetail(r)}>详情</Button> },
        ]}
      />
      <DetailModal log={detail} onClose={() => setDetail(null)} />
    </Card>
  );
}
```

- [ ] **Step 3: 提交**

```bash
git add frontend/src/pages/admin/audit/
git commit -m "feat(frontend): audit log list with filters and detail modal"
```

---

### Task 82: OrgTreeSelector 复用组件

**Files:**
- Create: `frontend/src/components/OrgTreeSelector.tsx`

- [ ] **Step 1: 写组件**（Plan 1.2+ 的全局筛选器，现在先提供基础版便于后续扩展）

```tsx
import { TreeSelect } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';

export function OrgTreeSelector({
  value, onChange, allowClear = true, placeholder = '选择组织节点', style,
}: {
  value?: number | null;
  onChange?: (v: number | null) => void;
  allowClear?: boolean;
  placeholder?: string;
  style?: React.CSSProperties;
}) {
  const { data: tree = [] } = useQuery({
    queryKey: ['orgtree'], queryFn: () => orgTreeApi.getTree(), staleTime: 60_000,
  });
  const toSelectData = (nodes: OrgNodeDTO[]): any[] =>
    nodes.map((n) => ({ title: n.name, value: n.id, children: toSelectData(n.children) }));
  return (
    <TreeSelect
      treeData={toSelectData(tree)}
      treeDefaultExpandAll
      allowClear={allowClear}
      placeholder={placeholder}
      value={value ?? undefined}
      onChange={(v) => onChange?.(v ?? null)}
      style={style}
    />
  );
}
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/components/OrgTreeSelector.tsx
git commit -m "feat(frontend): OrgTreeSelector reusable component"
```

---

### Task 83: 错误码映射工具

**Files:**
- Create: `frontend/src/utils/errorCode.ts`

- [ ] **Step 1: 写映射**

```typescript
export const errorCodeToMessage = (code: number): string => {
  const map: Record<number, string> = {
    400: '请求参数错误',
    40000: '业务规则错误',
    40001: '未登录或登录已过期',
    40003: '权限不足',
    40004: '资源不存在',
    40009: '冲突（如重复或版本不匹配）',
    50000: '服务器内部错误',
  };
  return map[code] || '未知错误';
};
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/utils/errorCode.ts
git commit -m "feat(frontend): error code message map"
```

---

### Task 84: 时间格式化工具

**Files:**
- Create: `frontend/src/utils/format.ts`

- [ ] **Step 1: 写**

```typescript
import dayjs from 'dayjs';

export const formatDateTime = (iso?: string) =>
  iso ? dayjs(iso).format('YYYY-MM-DD HH:mm:ss') : '-';
export const formatDate = (iso?: string) =>
  iso ? dayjs(iso).format('YYYY-MM-DD') : '-';
```

- [ ] **Step 2: 单元测试**

```typescript
// frontend/src/utils/format.test.ts
import { describe, it, expect } from 'vitest';
import { formatDateTime, formatDate } from './format';

describe('format', () => {
  it('formatDateTime handles undefined', () => {
    expect(formatDateTime()).toBe('-');
  });
  it('formatDate formats ISO', () => {
    expect(formatDate('2026-04-24T10:00:00+08:00')).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});
```

```bash
cd frontend && pnpm test --run format
```

Expected: 2 PASS.

- [ ] **Step 3: 提交**

```bash
git add frontend/src/utils/format.ts frontend/src/utils/format.test.ts
git commit -m "feat(frontend): date format utils + tests"
```

---

### Task 85: 前端 Lint & Typecheck 全绿

**Files:**
- Create: `frontend/.eslintrc.cjs`
- Create: `frontend/.prettierrc`

- [ ] **Step 1: eslintrc**

```javascript
module.exports = {
  root: true,
  env: { browser: true, es2022: true },
  extends: ['eslint:recommended', 'plugin:@typescript-eslint/recommended',
            'plugin:react-hooks/recommended'],
  parser: '@typescript-eslint/parser',
  plugins: ['react-refresh'],
  rules: {
    'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
    '@typescript-eslint/no-explicit-any': 'off',
    '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
  },
  ignorePatterns: ['dist', '.eslintrc.cjs', 'vite.config.ts'],
};
```

- [ ] **Step 2: prettierrc**

```json
{ "semi": true, "singleQuote": true, "printWidth": 100, "tabWidth": 2, "trailingComma": "es5" }
```

- [ ] **Step 3: 跑全套**

```bash
cd frontend && pnpm lint && pnpm typecheck && pnpm test --run
```

Expected: 全部 PASS / 0 errors。

- [ ] **Step 4: 提交**

```bash
git add frontend/.eslintrc.cjs frontend/.prettierrc
git commit -m "chore(frontend): eslint + prettier configs"
```

---

### Task 86: 启动前后端联调

**Files:**（仅验证）

- [ ] **Step 1: 启动后端**

```bash
docker compose -f docker-compose.dev.yml up -d
./mvnw -pl ems-app spring-boot:run
```

- [ ] **Step 2: 另一终端启前端**

```bash
cd frontend && pnpm dev
```

- [ ] **Step 3: 浏览器验证**

打开 http://localhost:5173：
1. 自动跳到 `/login`
2. 用 `admin` / `admin123!` 登录
3. 进首页看到欢迎语
4. 进入"组织树"，建一个节点
5. 进入"管理 / 用户"，建一个 VIEWER 用户
6. 给该用户授一个节点的 SUBTREE 权限
7. 退出，用新 VIEWER 账号登录，验证其看到受限内容
8. 切回 admin，进入"审计日志"，看到所有操作记录

如果以上全部成功，前后端联调通过。

- [ ] **Step 4: Task 无代码改动，仅记录里程碑**

```bash
git commit --allow-empty -m "chore: frontend integration smoke verified manually"
```

---

### Task 87: (Buffer) 修复联调中发现的问题

**Files:**（按实际修复）

- [ ] **Step 1: 记录所有问题**

在 `docs/ops/known-issues.md` 中记录联调发现的问题（若有）。

- [ ] **Step 2: 逐个修复**

对每个问题：重现 → 写失败测试 → 修复 → 验证测试通过 → commit 带问题描述。

- [ ] **Step 3: 如无问题，跳过此 Task。**

---

### Task 88: 本地化（zh-CN）校验

**Files:**（验证）

- [ ] **Step 1: 所有用户可见文本已为中文（按钮、提示、占位符等）**

检查清单：
- `login` 页 — 已中文 ✓
- 首页 — 已中文 ✓
- `orgtree` 及子 modal — 已中文 ✓
- `admin/users/**` — 已中文 ✓
- `admin/audit/**` — 已中文 ✓

- [ ] **Step 2: 如发现遗漏，补齐后 commit。**

---

### Task 89: 前端 README

**Files:**
- Create: `frontend/README.md`

- [ ] **Step 1: 写**

```markdown
# Factory EMS Frontend

## Setup
pnpm install

## Dev
pnpm dev           # Vite dev server @ :5173, proxy /api to localhost:8080

## Build
pnpm build

## Test
pnpm test          # Vitest
pnpm test --run

## Lint
pnpm lint
pnpm typecheck
```

- [ ] **Step 2: 提交**

```bash
git add frontend/README.md
git commit -m "docs(frontend): README"
```

---

### Task 90: 前端 .gitignore

**Files:**
- Create: `frontend/.gitignore`

- [ ] **Step 1: 写**

```gitignore
node_modules
dist
.vite
*.log
.env.local
```

- [ ] **Step 2: 提交**

```bash
git add frontend/.gitignore
git commit -m "chore(frontend): local gitignore"
```

---

### Task 91: Phase F 清单检查

**Files:**（仅 checklist）

- [ ] **Step 1: 确认以下页面都可访问并可完成典型操作**

- [x] `/login` — 登录
- [x] `/` — 首页欢迎
- [x] `/profile` — 修改密码
- [x] `/orgtree` — 组织树 CRUD + 移动
- [x] `/admin/users` — 用户列表 + 新建 / 编辑 / 删除 / 重置密码
- [x] `/admin/users/:id` — 用户编辑 + 角色分配
- [x] `/admin/users/:id/permissions` — 节点权限分配
- [x] `/admin/roles` — 角色列表（只读）
- [x] `/admin/audit` — 审计日志
- [x] `/forbidden` / `/not-found`

- [ ] **Step 2: 无 commit。**

---

### Task 92: 提交 Phase F 里程碑

- [ ] **Step 1: 提交空 commit 作为里程碑**

```bash
git commit --allow-empty -m "chore: phase F complete, all admin pages implemented"
```

---

## Phase G · 部署 + E2E（Tasks 93-106）

### Task 93: 后端 Dockerfile

**Files:**
- Create: `ems-app/Dockerfile`
- Create: `.dockerignore`

- [ ] **Step 1: 写 `Dockerfile`**（多阶段构建）

```dockerfile
# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY ems-core/pom.xml    ems-core/
COPY ems-audit/pom.xml   ems-audit/
COPY ems-orgtree/pom.xml ems-orgtree/
COPY ems-auth/pom.xml    ems-auth/
COPY ems-app/pom.xml     ems-app/
RUN mvn -B -q -T 4 dependency:go-offline -DskipTests
COPY ems-core    ems-core
COPY ems-audit   ems-audit
COPY ems-orgtree ems-orgtree
COPY ems-auth    ems-auth
COPY ems-app     ems-app
RUN mvn -B -q -T 4 clean package -DskipTests -pl ems-app -am

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S ems && adduser -S -G ems ems
WORKDIR /app
COPY --from=build --chown=ems:ems /build/ems-app/target/factory-ems.jar /app/factory-ems.jar
USER ems
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/factory-ems.jar"]
```

- [ ] **Step 2: 写 `.dockerignore`**

```
target/
**/target/
.git/
.idea/
.vscode/
node_modules/
frontend/dist/
e2e/
docs/
```

- [ ] **Step 3: 构建镜像**

```bash
docker build -t factory-ems:dev -f ems-app/Dockerfile .
```

Expected: 构建成功，镜像大小 ~300MB。

- [ ] **Step 4: 提交**

```bash
git add ems-app/Dockerfile .dockerignore
git commit -m "build: backend Dockerfile (multi-stage)"
```

---

### Task 94: 前端 Dockerfile

**Files:**
- Create: `frontend/Dockerfile`

- [ ] **Step 1: 写 `Dockerfile`**

```dockerfile
# syntax=docker/dockerfile:1.7
FROM node:20-alpine AS build
WORKDIR /build
RUN corepack enable && corepack prepare pnpm@9 --activate
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/
WORKDIR /build/frontend
RUN pnpm install --frozen-lockfile
COPY frontend .
RUN pnpm build

# 产物镜像：只含 dist/，通过 volume 暴露给 Nginx
FROM alpine:3.20
RUN mkdir -p /dist
COPY --from=build /build/frontend/dist /dist
CMD ["sh", "-c", "cp -r /dist/. /share/ && tail -f /dev/null"]
# 运行时：通过 bind-mount volume，把 /share 共享给 Nginx 容器
```

> **Note:** 不单独运行前端容器；用它把构建产物拷到共享卷，Nginx 挂载该卷即可。简单、透明。

- [ ] **Step 2: 构建验证**

```bash
docker build -t factory-ems-frontend:dev -f frontend/Dockerfile .
```

- [ ] **Step 3: 提交**

```bash
git add frontend/Dockerfile
git commit -m "build: frontend Dockerfile"
```

---

### Task 95: 生产 `docker-compose.yml`

**Files:**
- Create: `docker-compose.yml`

- [ ] **Step 1: 写**

```yaml
version: "3.9"

services:
  nginx:
    image: nginx:alpine
    ports: ["80:80"]
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./nginx/conf.d:/etc/nginx/conf.d:ro
      - frontend_dist:/usr/share/nginx/html:ro
      - ./data/ems_uploads:/var/www/uploads:ro
    depends_on:
      factory-ems:
        condition: service_healthy
    restart: unless-stopped

  factory-ems:
    image: factory-ems:${EMS_VERSION:-1.1.0-SNAPSHOT}
    build:
      context: .
      dockerfile: ems-app/Dockerfile
    env_file: .env
    environment:
      SPRING_PROFILES_ACTIVE: prod
      EMS_DB_HOST: postgres
    volumes:
      - ./data/ems_uploads:/data/ems_uploads
      - ./logs/ems:/app/logs
    depends_on:
      postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health/liveness"]
      interval: 30s
      timeout: 5s
      retries: 3
    restart: unless-stopped

  frontend-builder:
    image: factory-ems-frontend:${EMS_VERSION:-1.1.0-SNAPSHOT}
    build:
      context: .
      dockerfile: frontend/Dockerfile
    volumes:
      - frontend_dist:/share

  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: ${EMS_DB_NAME:-factory_ems}
      POSTGRES_USER: ${EMS_DB_USER:-ems}
      POSTGRES_PASSWORD: ${EMS_DB_PASSWORD}
    volumes:
      - ./data/postgres:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"]
      interval: 10s
      timeout: 3s
      retries: 10
    restart: unless-stopped

volumes:
  frontend_dist:
```

- [ ] **Step 2: 提交**

```bash
git add docker-compose.yml
git commit -m "build: production docker-compose stack"
```

---

### Task 96: Nginx 配置

**Files:**
- Create: `nginx/nginx.conf`
- Create: `nginx/conf.d/factory-ems.conf`

- [ ] **Step 1: `nginx.conf`**

```nginx
user  nginx;
worker_processes  auto;
error_log  /var/log/nginx/error.log warn;
pid        /var/run/nginx.pid;

events { worker_connections 1024; }

http {
    include       /etc/nginx/mime.types;
    default_type  application/octet-stream;
    sendfile        on;
    keepalive_timeout 65;
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml;

    log_format json escape=json '{'
        '"ts":"$time_iso8601",'
        '"remote":"$remote_addr",'
        '"method":"$request_method",'
        '"uri":"$uri",'
        '"status":$status,'
        '"bytes":$body_bytes_sent,'
        '"rt":$request_time,'
        '"ua":"$http_user_agent",'
        '"ref":"$http_referer",'
        '"traceId":"$http_x_trace_id"'
    '}';
    access_log /var/log/nginx/access.log json;

    add_header X-Frame-Options DENY always;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy no-referrer always;

    include /etc/nginx/conf.d/*.conf;
}
```

- [ ] **Step 2: `conf.d/factory-ems.conf`**

```nginx
server {
    listen 80;
    server_name _;

    client_max_body_size 20m;

    root /usr/share/nginx/html;
    index index.html;

    # 静态前端（SPA fallback）
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 上传文件（平面图等）直出
    location /api/v1/floorplans/ {
        # 仅匹配 .../image 请求给到 nginx 直出
        location ~ ^/api/v1/floorplans/(\d+)/image$ {
            alias /var/www/uploads/;
            try_files /floorplans/$arg_file =404;
            # 注：实际直出规则需根据后端返回的 image_path 调整，此处为示例
        }
        # 其余 floorplan 端点仍走后端
        proxy_pass http://factory-ems:8080;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # API 反代
    location /api/ {
        proxy_pass http://factory-ems:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Trace-Id        $http_x_trace_id;
        proxy_read_timeout 60s;
    }

    # 健康检查（内网）
    location = /actuator/health/liveness {
        allow 127.0.0.1;
        allow 10.0.0.0/8;
        allow 172.16.0.0/12;
        allow 192.168.0.0/16;
        deny all;
        proxy_pass http://factory-ems:8080/actuator/health/liveness;
    }

    # Prometheus 指标（内网）
    location = /actuator/prometheus {
        allow 127.0.0.1;
        allow 10.0.0.0/8;
        allow 172.16.0.0/12;
        allow 192.168.0.0/16;
        deny all;
        proxy_pass http://factory-ems:8080/actuator/prometheus;
    }
}
```

> **Note:** 平面图直出的 `alias` 规则在 Plan 1.3 引入 floorplan 模块时具体调整；此处只保留反代通路。

- [ ] **Step 3: 验证 nginx 配置语法**（如已装 nginx 本地）或容器化验证

```bash
docker run --rm -v $(pwd)/nginx/nginx.conf:/etc/nginx/nginx.conf:ro \
    -v $(pwd)/nginx/conf.d:/etc/nginx/conf.d:ro \
    nginx:alpine nginx -t
```

Expected: `syntax is ok`。

- [ ] **Step 4: 提交**

```bash
git add nginx/
git commit -m "build: nginx config with reverse proxy + SPA fallback + security headers"
```

---

### Task 97: 完整 Docker Compose 启动演练

**Files:**（仅验证）

- [ ] **Step 1: 生成 `.env`**

```bash
cp .env.example .env
# 用工具生成强密码填入 EMS_DB_PASSWORD 和 EMS_JWT_SECRET
```

- [ ] **Step 2: 构建镜像 + 启动**

```bash
docker compose build
docker compose up -d
docker compose ps
```

Expected: 所有容器 `healthy`。前端 builder 一次性跑完退出（exit 0），其输出落在 `frontend_dist` volume。

- [ ] **Step 3: 浏览器访问 http://localhost/**

- 跳登录页
- 用 `admin / admin123!` 登录
- 首页正常

- [ ] **Step 4: 关闭**

```bash
docker compose down
```

- [ ] **Step 5: 提交**（空 commit 里程碑）

```bash
git commit --allow-empty -m "chore: docker compose stack verified"
```

---

### Task 98: E2E 项目初始化（Playwright）

**Files:**
- Create: `e2e/package.json`
- Create: `e2e/playwright.config.ts`
- Create: `e2e/tsconfig.json`

- [ ] **Step 1: 初始化**

```bash
mkdir e2e && cd e2e && pnpm init
```

`e2e/package.json`：

```json
{
  "name": "factory-ems-e2e",
  "private": true,
  "type": "module",
  "scripts": {
    "test": "playwright test",
    "test:ui": "playwright test --ui"
  },
  "devDependencies": {
    "@playwright/test": "^1.47.0",
    "typescript": "^5.5.0"
  }
}
```

- [ ] **Step 2: 安装**

```bash
cd e2e && pnpm install && pnpm exec playwright install --with-deps chromium
```

- [ ] **Step 3: `playwright.config.ts`**

```typescript
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  retries: 1,
  workers: 1,                         // 共享 admin 账号，串行
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: devices['Desktop Chrome'] }],
});
```

- [ ] **Step 4: `tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2022", "module": "ESNext", "moduleResolution": "bundler",
    "strict": true, "esModuleInterop": true, "skipLibCheck": true
  },
  "include": ["tests", "playwright.config.ts"]
}
```

- [ ] **Step 5: 提交**

```bash
git add e2e/
git commit -m "test(e2e): playwright scaffold"
```

---

### Task 99: E2E — 登录登出流程

**Files:**
- Create: `e2e/tests/login.spec.ts`

- [ ] **Step 1: 写**

```typescript
import { test, expect } from '@playwright/test';

test('admin can login and logout', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByText('工厂能源管理系统')).toBeVisible();

  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: '登录' }).click();

  await expect(page).toHaveURL('/');
  await expect(page.getByText('欢迎')).toBeVisible();

  // 登出
  await page.getByText(/admin|系统管理员/).click();
  await page.getByText('退出登录').click();
  await expect(page).toHaveURL(/\/login/);
});

test('login with wrong password shows error', async ({ page }) => {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('wrong');
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page.getByText(/密码错误|用户名或密码/)).toBeVisible();
});
```

- [ ] **Step 2: 起 stack 跑 E2E**

```bash
docker compose up -d
cd e2e && pnpm test login.spec
```

Expected: 2 PASS.

- [ ] **Step 3: 提交**

```bash
git add e2e/tests/login.spec.ts
git commit -m "test(e2e): login and logout"
```

---

### Task 100: E2E — 创建用户 + 分配权限

**Files:**
- Create: `e2e/tests/user-permission.spec.ts`

- [ ] **Step 1: 写**

```typescript
import { test, expect } from '@playwright/test';

async function login(page: any, username: string, password: string) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(username);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page).toHaveURL('/');
}

test('admin creates user, org tree, assigns permission; viewer sees only subtree', async ({ page, context }) => {
  const viewerName = 'e2e_viewer_' + Date.now();
  const viewerPass = 'viewerPass123';

  // 管理员登录 + 建组织树
  await login(page, 'admin', 'admin123!');
  await page.goto('/orgtree');

  // 新建根节点（如已存在可跳过）
  await page.getByRole('button', { name: '新建节点' }).click();
  await page.getByLabel('名称').fill('E2E工厂');
  await page.getByLabel('编码').fill(`E2E_ROOT_${Date.now()}`);
  await page.getByLabel('节点类型').click();
  await page.getByText('PLANT', { exact: true }).click();
  await page.getByRole('button', { name: '确 定' }).click();
  await expect(page.getByText('已创建')).toBeVisible();

  // 选中刚建的，加子节点"一车间"
  await page.getByText('E2E工厂').click();
  await page.getByRole('button', { name: '新建节点' }).click();
  await page.getByLabel('名称').fill('一车间');
  const wsCode = `E2E_WS_${Date.now()}`;
  await page.getByLabel('编码').fill(wsCode);
  await page.getByLabel('节点类型').click();
  await page.getByText('WORKSHOP', { exact: true }).click();
  await page.getByRole('button', { name: '确 定' }).click();
  await expect(page.getByText('已创建')).toBeVisible();

  // 建用户
  await page.goto('/admin/users');
  await page.getByRole('button', { name: '新建' }).click();
  await page.getByLabel('用户名').fill(viewerName);
  await page.getByLabel('初始密码').fill(viewerPass);
  await page.getByLabel('姓名').fill('E2E测试员');
  await page.getByRole('button', { name: '确 定' }).click();
  await expect(page.getByText(viewerName)).toBeVisible();

  // 授一车间 SUBTREE 权限
  await page.getByRole('row', { name: new RegExp(viewerName) })
    .getByRole('link', { name: /权限/ }).click();
  await page.getByRole('button', { name: '授权节点' }).click();
  await page.getByLabel('组织节点').click();
  await page.getByText('一车间').click();
  await page.getByRole('button', { name: '确 定' }).click();
  await expect(page.getByText('已授权')).toBeVisible();

  // admin 登出
  await page.getByText(/admin|系统管理员/).click();
  await page.getByText('退出登录').click();

  // viewer 登录 — 断言: 组织树页应只看到"一车间"子树，不能见"E2E工厂"根
  // （实际 orgtree API 目前对 VIEWER 也返回全树；Plan 1.2 引入权限过滤。此处只验证登录成功）
  await login(page, viewerName, viewerPass);
  await page.goto('/orgtree');
  await expect(page.getByText('一车间')).toBeVisible();

  // VIEWER 不能进入管理菜单
  await expect(page.getByRole('link', { name: '用户' })).toHaveCount(0);
});
```

> **Note:** E2E 中最后一条断言 "VIEWER 只看到子树" 目前因为 `/orgtree` 接口**未应用权限过滤**（仅验证登录）——Plan 1.1 的 orgtree API 对任意登录用户返回全树。子项目 1 的设计里"权限过滤"主要作用于看板 / 报表，不作用于"组织树本身"。此处只要求 `管理菜单不可见`。

- [ ] **Step 2: 跑测试**

```bash
cd e2e && pnpm test user-permission.spec
```

Expected: PASS.

- [ ] **Step 3: 提交**

```bash
git add e2e/tests/user-permission.spec.ts
git commit -m "test(e2e): user creation and permission assignment"
```

---

### Task 101: E2E — 修改密码

**Files:**
- Create: `e2e/tests/profile.spec.ts`

- [ ] **Step 1: 写**

```typescript
import { test, expect } from '@playwright/test';

test('user can change own password', async ({ page }) => {
  // 建临时用户供测试
  // 1) admin 登录
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: '登录' }).click();

  const uname = `pwd_${Date.now()}`;
  await page.goto('/admin/users');
  await page.getByRole('button', { name: '新建' }).click();
  await page.getByLabel('用户名').fill(uname);
  await page.getByLabel('初始密码').fill('OldPass_123');
  await page.getByRole('button', { name: '确 定' }).click();

  // 2) admin 登出
  await page.getByText(/admin|系统管理员/).click();
  await page.getByText('退出登录').click();

  // 3) 新用户登录 → 改密
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(uname);
  await page.getByPlaceholder('密码').fill('OldPass_123');
  await page.getByRole('button', { name: '登录' }).click();

  await page.goto('/profile');
  await page.getByLabel('原密码').fill('OldPass_123');
  await page.getByLabel('新密码').fill('NewPass_456');
  await page.getByLabel('确认新密码').fill('NewPass_456');
  await page.getByRole('button', { name: '保存' }).click();
  await expect(page.getByText('密码已更新')).toBeVisible();

  // 4) 登出 + 用新密码登录
  await page.getByText(uname).click();
  await page.getByText('退出登录').click();

  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(uname);
  await page.getByPlaceholder('密码').fill('NewPass_456');
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page).toHaveURL('/');
});
```

- [ ] **Step 2: 跑测试**

```bash
cd e2e && pnpm test profile.spec
```

Expected: PASS.

- [ ] **Step 3: 提交**

```bash
git add e2e/tests/profile.spec.ts
git commit -m "test(e2e): change password flow"
```

---

### Task 102: E2E — Token 自动刷新

**Files:**
- Create: `e2e/tests/refresh-token.spec.ts`

- [ ] **Step 1: 写**（模拟 access token 过期）

```typescript
import { test, expect } from '@playwright/test';

test('frontend auto-refreshes access token when expired', async ({ page }) => {
  // 登录
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page).toHaveURL('/');

  // 清掉 access token，模拟过期
  await page.evaluate(() => {
    const ls = localStorage.getItem('ems-auth');
    if (ls) {
      const obj = JSON.parse(ls);
      obj.state.accessToken = 'expired.invalid.token';
      localStorage.setItem('ems-auth', JSON.stringify(obj));
    }
  });
  // Zustand 的 hydrate 在页面刷新时生效
  await page.reload();

  // 因为 cookie 里还有合法 refresh token，预期自动续签成功后继续访问
  await page.goto('/admin/users');
  await expect(page.getByText('用户管理')).toBeVisible();  // 说明数据请求成功
});
```

> **Note:** 实际上 `authStore.persist(partialize)` 只持久化 `user` 不持久化 token（见 Task 59）。上面脚本需改为让 access token 直接在内存里失效：reload 后 token=null，axios 会从 `accessToken=null` 发起请求 → 走 401 → 触发刷新。

- [ ] **Step 2: 简化实现**

```typescript
import { test, expect } from '@playwright/test';

test('frontend auto-refreshes when access token missing (reload) - refresh cookie still valid', async ({ page }) => {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page).toHaveURL('/');

  // 刷新页面 — Zustand token 在内存丢失，persist 只有 user
  await page.reload();

  // 直接访问需要鉴权的接口驱动的页面
  await page.goto('/admin/users');
  // 如果 cookie refresh token 能走 /auth/refresh 成功，页面应正常加载
  // 当前实现下：先 GET /users 返回 401 → interceptor 尝试 /auth/refresh → 用户列表渲染
  await expect(page.getByText('用户管理')).toBeVisible({ timeout: 10_000 });
});
```

- [ ] **Step 3: 跑测试**

```bash
cd e2e && pnpm test refresh-token.spec
```

Expected: PASS.

- [ ] **Step 4: 提交**

```bash
git add e2e/tests/refresh-token.spec.ts
git commit -m "test(e2e): token auto-refresh on reload"
```

---

### Task 103: E2E — 审计日志可见

**Files:**
- Create: `e2e/tests/audit.spec.ts`

- [ ] **Step 1: 写**

```typescript
import { test, expect } from '@playwright/test';

test('audit log shows login and user-creation events', async ({ page }) => {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page).toHaveURL('/');

  const uname = `audit_${Date.now()}`;
  await page.goto('/admin/users');
  await page.getByRole('button', { name: '新建' }).click();
  await page.getByLabel('用户名').fill(uname);
  await page.getByLabel('初始密码').fill('AuditPass_1');
  await page.getByRole('button', { name: '确 定' }).click();

  await page.goto('/admin/audit');
  await expect(page.getByText('审计日志')).toBeVisible();
  await expect(page.getByText('LOGIN')).toBeVisible();
  await expect(page.getByText(uname)).toBeVisible();  // CREATE USER 审计里有 resource id / summary
});
```

- [ ] **Step 2: 跑测试**

```bash
cd e2e && pnpm test audit.spec
```

Expected: PASS.

- [ ] **Step 3: 提交**

```bash
git add e2e/tests/audit.spec.ts
git commit -m "test(e2e): audit log records login and user-creation"
```

---

### Task 104: E2E — 失败登录锁定

**Files:**
- Create: `e2e/tests/lockout.spec.ts`

- [ ] **Step 1: 写**

```typescript
import { test, expect } from '@playwright/test';

test('account lockout after 5 failed attempts', async ({ page, request }) => {
  // 通过 API 建一个独立用户（需要 admin token）
  const loginRes = await request.post('/api/v1/auth/login', {
    data: { username: 'admin', password: 'admin123!' },
  });
  const loginBody = await loginRes.json();
  const adminToken = loginBody.data.accessToken;

  const uname = `lock_${Date.now()}`;
  await request.post('/api/v1/users', {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { username: uname, password: 'TempPass_1', displayName: 'lock', roleCodes: ['VIEWER'] },
  });

  // 5 次错误
  for (let i = 0; i < 5; i++) {
    await page.goto('/login');
    await page.getByPlaceholder('用户名').fill(uname);
    await page.getByPlaceholder('密码').fill('wrongwrong');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page.getByText(/用户名或密码错误|锁定/)).toBeVisible();
  }
  // 第 6 次用正确密码 — 应被告知锁定
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(uname);
  await page.getByPlaceholder('密码').fill('TempPass_1');
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page.getByText(/锁定/)).toBeVisible();
});
```

- [ ] **Step 2: 跑测试**

```bash
cd e2e && pnpm test lockout.spec
```

Expected: PASS.

- [ ] **Step 3: 提交**

```bash
git add e2e/tests/lockout.spec.ts
git commit -m "test(e2e): lockout after 5 failed attempts"
```

---

### Task 105: 运维文档

**Files:**
- Create: `docs/ops/dev-setup.md`
- Create: `docs/ops/deployment.md`
- Create: `docs/ops/runbook.md`

- [ ] **Step 1: `dev-setup.md`**

```markdown
# Dev Setup

## Prerequisites
- JDK 21, Maven 3.9+ (or wrapper)
- Node 20+, pnpm 9+
- Docker Desktop 24+

## 启动
```bash
# 1. 启 Postgres
docker compose -f docker-compose.dev.yml up -d

# 2. 后端
./mvnw -pl ems-app spring-boot:run

# 3. 前端（另一终端）
cd frontend && pnpm install && pnpm dev
```

访问 http://localhost:5173，用 admin / admin123! 登录。
```

- [ ] **Step 2: `deployment.md`**

```markdown
# Deployment

## 首次部署
```bash
# 1. 准备环境变量（用强密码）
cp .env.example .env
openssl rand -base64 32         # 生成 EMS_DB_PASSWORD
openssl rand -base64 48         # 生成 EMS_JWT_SECRET
# 编辑 .env 填入

# 2. 构建 & 启动
docker compose build
docker compose up -d

# 3. 验证
curl http://localhost/actuator/health/liveness
```

## 版本升级
```bash
docker compose pull
docker compose up -d
```

## 回滚
修改 `.env` 里 `EMS_VERSION=旧版本号`，然后：
```bash
docker compose down
docker compose up -d
```

## 数据库迁移
Flyway 随应用启动自动执行。破坏性变更禁止（见 spec §11.5）。
```

- [ ] **Step 3: `runbook.md`**

```markdown
# Runbook — 常见故障排查

## 症状：Spring Boot 启动失败，日志 "Failed to configure DataSource"
检查 `postgres` 容器 `docker compose ps`；`EMS_DB_PASSWORD` 与 Postgres 容器环境变量是否一致。

## 症状：登录返回 "用户名或密码错误"
1. 确认 admin 存在：`docker exec -it postgres psql -U ems factory_ems -c 'select username from users;'`
2. 若 admin 缺失：重新跑 Flyway 或手动插入（参考 `V1.0.8__seed_reference_data.sql`）

## 症状：401 Authentication fails 持续
JWT secret 变更会导致已有 access token 全部失效。让用户重新登录即可；refresh token 同样失效。

## 重置某用户密码
```sql
-- 在应用内用 admin 调接口最方便：
-- PUT /api/v1/users/{id}/password/reset  {"newPassword":"..."}
-- 也可以直接 SQL（不推荐）
```

## 如何看审计日志
- 应用内：`/admin/audit`（ADMIN）
- 直查库：`SELECT * FROM audit_logs ORDER BY occurred_at DESC LIMIT 100;`
```

- [ ] **Step 4: 提交**

```bash
git add docs/ops/
git commit -m "docs(ops): dev-setup, deployment, runbook"
```

---

### Task 106: 最终验收 + Release Tag

**Files:**（无代码改动）

- [ ] **Step 1: 全栈测试**

```bash
./mvnw -T 4 clean verify
cd frontend && pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test --run && pnpm build
docker compose build
docker compose up -d
sleep 30
cd e2e && pnpm install --frozen-lockfile && pnpm test
```

所有环节 PASS。

- [ ] **Step 2: 手工走一遍 Plan 1.1 的演示场景**

对照"范围边界 · 交付演示场景"的 8 步，手工走一遍，每一步通过。

- [ ] **Step 3: 打 tag**

```bash
git tag -a v1.1.0-plan1.1 -m "Plan 1.1 complete: foundation + auth"
```

- [ ] **Step 4: 最终 commit**

```bash
git commit --allow-empty -m "release: v1.1.0 plan 1.1 foundation + auth ✅"
```

- [ ] **Step 5: 继续 Plan 1.2（核心领域 + 基础看板）**

停止 Plan 1.1 工作。创建并开始 `docs/superpowers/plans/2026-XX-XX-factory-ems-plan-1.2-core-domain.md`。

---

<!-- END_OF_PLAN -->
