FROM rust:1.95.0-alpine AS builder

WORKDIR /app
COPY Cargo.toml Cargo.lock ./
COPY src ./src
RUN cargo build --release --locked

FROM alpine:latest
WORKDIR /app
COPY --from=builder /app/target/release/resize-demo-rust .
EXPOSE 8080
CMD ["./resize-demo-rust"]
