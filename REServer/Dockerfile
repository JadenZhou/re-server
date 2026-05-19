FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*-jar-with-dependencies.jar /app/app.jar
EXPOSE 7070
VOLUME /data
CMD ["java", "-jar", "/app/app.jar"]
