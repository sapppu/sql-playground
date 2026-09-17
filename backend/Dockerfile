# ---- build stage: full JDK + Maven toolchain (not shipped) ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

# ---- run stage: slim JRE only ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# Rename to a fixed name: `cp src... dest-file` fails LOUDLY if the
# build ever emits more than one jar (a wildcard COPY would not).
COPY --from=build /app/target/*.jar /tmp/build-jars/
RUN cp /tmp/build-jars/*.jar /app/app.jar && rm -rf /tmp/build-jars
# App data (WAL, users, history) lives here; mount a volume for persistence.
VOLUME ["/app/data"]
EXPOSE 8081
# Render-style hosts inject $PORT; default keeps local/compose behavior.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --server.port=${PORT:-8081}"]
