# Estágio 1: compila o jar com JDK completo
FROM eclipse-temurin:21-jdk@sha256:db5cc6341a1d7bc40cdb51e1f0d9824f3bb2cc8107d29da421dc4df7d11f05f1 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw -B -q package -DskipTests

# Estágio 2: imagem final só com JRE e o jar
FROM eclipse-temurin:21-jre@sha256:ca73b4d3961703d6d7830a4361f49ef0478f8f28b39defc07bb72f4cc7f834f6
WORKDIR /app
RUN useradd --system --uid 1001 app
USER app
COPY --from=build /workspace/target/ticket-system-*.jar app.jar
EXPOSE 8081
# Heap = 65% do limite do container: o resto fica para metaspace, code cache, pilhas e buffers
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "app.jar"]
