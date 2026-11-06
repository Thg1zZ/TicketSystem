# Estágio 1: compila o jar com JDK completo
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw -B -q package -DskipTests

# Estágio 2: imagem final só com JRE e o jar
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
USER app
COPY --from=build /workspace/target/ticket-system-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
