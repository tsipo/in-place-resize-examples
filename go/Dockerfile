FROM golang:1.26-alpine AS builder

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY main.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -o resize-demo-go main.go

FROM alpine:latest
WORKDIR /root/
COPY --from=builder /app/resize-demo-go .
EXPOSE 8080
CMD ["./resize-demo-go"]
