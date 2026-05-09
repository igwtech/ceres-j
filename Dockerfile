FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src/ src/
# BytesIdenticalAssertion tests load retail samples from
# docs/protocol/_data/packets.json — copy the catalog into the
# build stage so the parity tests can run during `mvn test`.
# Runtime stage doesn't need this; it's test-time only.
COPY docs/protocol/_data/ docs/protocol/_data/
RUN mvn clean test -B
RUN mvn clean package -B -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /build/bin/ceres-j-1.0-SNAPSHOT.jar /app/ceres.jar
COPY --from=build /build/bin/*.jar /app/lib/
COPY database/ /app/database/

RUN mkdir -p /app/log

EXPOSE 5000/udp 7000 8020 8080 12000

ENTRYPOINT ["java", "-jar", "/app/ceres.jar"]
