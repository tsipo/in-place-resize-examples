FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /app
COPY Main.java ./
RUN javac --release 25 Main.java

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=builder /app/Main.class ./
COPY --from=builder /app/Main\$RuntimeLimits.class ./
COPY --from=builder /app/Main\$RuntimeLimitsMXBean.class ./
EXPOSE 8080
CMD ["java", "-XX:+UseContainerSupport", "Main"]
