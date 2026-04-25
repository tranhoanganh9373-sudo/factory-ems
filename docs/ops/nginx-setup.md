# Nginx Setup — Floorplan Static-Direct Serving (Plan 1.3 / Phase S)

## Why bypass Spring for floorplan images?

The factory-floor floorplan images are pure static binary blobs (PNG/JPG/WebP/SVG)
that are uploaded once and read many times by every dashboard client. Routing
each `GET /api/v1/floorplans/{id}/image` through Spring Boot has three
problems:

1. **MIME / Content-Type handling** — Nginx auto-resolves `Content-Type` from
   the file extension via its built-in `mime.types`. Spring would have to map
   each upload manually and write the header itself.
2. **Caching** — Nginx can emit `Cache-Control: public, max-age=86400, immutable`
   plus `Last-Modified`/`ETag` for free. Spring's static resource handler can
   do this too, but only if we wire `ResourceHttpRequestHandler` and disable
   the dispatcher servlet's default headers.
3. **Range / partial requests** — Mobile clients and progressive image loaders
   may send `Range:` requests. Nginx supports byte-range out of the box
   (`Accept-Ranges: bytes`); Spring's `ResourceHttpRequestHandler` supports it
   but adds JVM allocation and GC churn for large images.
4. **Throughput** — `sendfile on` + `tcp_nopush on` lets the kernel splice the
   file directly to the socket, never touching userspace. Spring would always
   buffer through the JVM.

For these reasons Plan 1.3 / Phase D explicitly deferred image bytes to Nginx;
the backend only owns metadata (size, MIME, uploader, image_path).

## File layout on disk

```
${EMS_UPLOAD_ROOT}/                          # docker volume → ./data/ems_uploads
├── floorplans/
│   ├── 2026-04/
│   │   ├── 6b1f8a3e-9d12-4c2a-b1e7-0f9c3d4a5e7b.png
│   │   └── ...
│   ├── 2026-05/
│   │   └── ...
│   └── ...
└── exports/                                 # report fileToken artefacts
    └── ad-hoc-2026-04-25T123042Z.csv
```

The relative path stored in column `floorplans.image_path` is for example
`2026-04/6b1f8a3e-9d12-4c2a-b1e7-0f9c3d4a5e7b.png`.

## Routing

Two strategies are shipped; pick one when wiring the production deploy.

### Strategy A — public alias (default)

```nginx
location ~ ^/api/v1/floorplans/(?<fp_id>[0-9]+)/image$ {
    alias /var/www/uploads/floorplans/$arg_path;
    expires 1d;
    sendfile on;
    add_header Accept-Ranges bytes;
}
```

The frontend calls `GET /api/v1/floorplans/{id}` (Spring) to obtain metadata
including `image_url = "/api/v1/floorplans/42/image?path=2026-04/abcd.png"`.
The browser then issues that URL; Nginx never hits Spring for the bytes.

**Trade-off:** any user who can guess a `(id, path)` pair can read the image —
acceptable for shop-floor diagrams where authentication is enforced for the
metadata endpoint and only known paths are returned to clients.

### Strategy B — X-Accel-Redirect

```nginx
location ^~ /_protected_floorplans/ {
    internal;
    alias /var/www/uploads/floorplans/;
}
```

Spring authorises each request and replies:

```
HTTP/1.1 200 OK
X-Accel-Redirect: /_protected_floorplans/2026-04/abcd.png
Content-Type: image/png
```

Nginx detects the header, treats the response as if the client requested
`/_protected_floorplans/...` (which is `internal;` and unreachable from the
network), and streams the file. The auth check still costs one Spring hop,
but bytes never enter the JVM.

Use this strategy when the floorplan must enforce per-user ACL (e.g. a shop
floor only visible to that org subtree). Toggle by removing Strategy A's
`location` block from `nginx/conf.d/factory-ems.conf` and ensuring the
backend writes `X-Accel-Redirect` for `GET /api/v1/floorplans/{id}/image`.

## Wire-up checklist

1. `docker-compose.yml` already mounts the upload root read-only into nginx:
   ```yaml
   volumes:
     - ./data/ems_uploads:/var/www/uploads:ro
   ```
2. Copy `deploy/nginx/floorplan.conf` into `nginx/conf.d/` (or `include` it
   from `factory-ems.conf`). The default file in `nginx/conf.d/factory-ems.conf`
   already has a placeholder `location ~ ^/api/v1/floorplans/(\d+)/image$`
   block — replace that placeholder with the contents of
   `deploy/nginx/floorplan.conf`.
3. `docker compose exec nginx nginx -t` — confirm the config parses.
4. `docker compose exec nginx nginx -s reload`.
5. Smoke test: `curl -I http://localhost:8888/api/v1/floorplans/1/image?path=2026-04/abcd.png`
   should return `200`, `Content-Type: image/png`, and `Cache-Control:
   public, max-age=86400, immutable`. The Spring access log should NOT
   contain a hit for that URL.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| 404 for valid path | `data/ems_uploads` not mounted in nginx | Add the volume in `docker-compose.yml`, restart |
| 403 for valid path | `nginx` user can't read the upload directory | `chown -R nginx:nginx data/ems_uploads` (or 0755) |
| 400 "path query parameter required" | Frontend forgot `?path=...` | Backend must return full URL incl. query in metadata response |
| Images cached forever | `expires 1d` retains stale image after re-upload | Re-upload assigns new uuid filename → new URL → cache busts naturally |
