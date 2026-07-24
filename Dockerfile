FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY target/ledger-0.0.1-SNAPSHOT.jar app.jar

# HTTP ports (8080-8082) + Raft gRPC ports (9864-9866)
EXPOSE 8080 8081 8082 9864 9865 9866

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

