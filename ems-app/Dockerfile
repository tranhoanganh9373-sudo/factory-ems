# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY ems-core/pom.xml    ems-core/
COPY ems-audit/pom.xml   ems-audit/
COPY ems-orgtree/pom.xml ems-orgtree/
COPY ems-auth/pom.xml    ems-auth/
COPY ems-meter/pom.xml   ems-meter/
COPY ems-timeseries/pom.xml ems-timeseries/
COPY ems-app/pom.xml     ems-app/
RUN mvn -B -q -T 4 dependency:go-offline -DskipTests
COPY ems-core    ems-core
COPY ems-audit   ems-audit
COPY ems-orgtree ems-orgtree
COPY ems-auth    ems-auth
COPY ems-meter   ems-meter
COPY ems-timeseries ems-timeseries
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
