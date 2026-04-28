# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY ems-core/pom.xml       ems-core/
COPY ems-audit/pom.xml      ems-audit/
COPY ems-orgtree/pom.xml    ems-orgtree/
COPY ems-auth/pom.xml       ems-auth/
COPY ems-meter/pom.xml      ems-meter/
COPY ems-tariff/pom.xml     ems-tariff/
COPY ems-production/pom.xml ems-production/
COPY ems-floorplan/pom.xml  ems-floorplan/
COPY ems-timeseries/pom.xml ems-timeseries/
COPY ems-dashboard/pom.xml  ems-dashboard/
COPY ems-report/pom.xml     ems-report/
COPY ems-cost/pom.xml       ems-cost/
COPY ems-billing/pom.xml    ems-billing/
COPY ems-collector/pom.xml  ems-collector/
COPY ems-app/pom.xml        ems-app/
COPY tools/mock-data-generator/pom.xml tools/mock-data-generator/
COPY tools/modbus-simulator/pom.xml    tools/modbus-simulator/
# `-pl ems-app -am` 沿 ems-app 的依赖树跳过 tools/* 子模块（都不是 ems-app 的依赖），
# 但根 POM 的 <modules> 校验仍要求每个声明过的子模块目录至少要有 pom.xml。
RUN mvn -B -q -T 4 -pl ems-app -am dependency:go-offline -DskipTests

COPY ems-core       ems-core
COPY ems-audit      ems-audit
COPY ems-orgtree    ems-orgtree
COPY ems-auth       ems-auth
COPY ems-meter      ems-meter
COPY ems-tariff     ems-tariff
COPY ems-production ems-production
COPY ems-floorplan  ems-floorplan
COPY ems-timeseries ems-timeseries
COPY ems-dashboard  ems-dashboard
COPY ems-report     ems-report
COPY ems-cost       ems-cost
COPY ems-billing    ems-billing
COPY ems-collector  ems-collector
COPY ems-app        ems-app
COPY tools          tools
RUN mvn -B -q -T 4 clean package -DskipTests -pl ems-app -am

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S ems && adduser -S -G ems ems
WORKDIR /app
COPY --from=build --chown=ems:ems /build/ems-app/target/factory-ems.jar /app/factory-ems.jar
USER ems
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/factory-ems.jar"]
