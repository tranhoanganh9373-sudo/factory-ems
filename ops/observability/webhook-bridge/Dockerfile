# Stage 1: build
FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY main.go ./
ENV CGO_ENABLED=0 GOOS=linux GOARCH=amd64
RUN go build -trimpath -ldflags="-s -w" -o /webhook-bridge .

# Stage 2: distroless runtime
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /webhook-bridge /webhook-bridge
USER 65532:65532
EXPOSE 9094
ENTRYPOINT ["/webhook-bridge"]
