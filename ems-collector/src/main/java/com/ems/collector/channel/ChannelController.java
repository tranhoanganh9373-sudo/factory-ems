package com.ems.collector.channel;

import com.ems.collector.channel.csv.ChannelCsvParser;
import com.ems.collector.transport.ChannelTransportFactory;
import com.ems.collector.transport.TestResult;
import com.ems.collector.transport.Transport;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Channel CRUD + 连接测试 REST 端点。
 *
 * <p>所有端点 ADMIN-only。前端 Phase 8 配置 UI 直接调用。
 */
@RestController
@RequestMapping("/api/v1/channel")
@PreAuthorize("hasRole('ADMIN')")
public class ChannelController {

    private final ChannelService service;
    private final ChannelRepository repo;
    private final ChannelTransportFactory factory;

    public ChannelController(ChannelService service,
                             ChannelRepository repo,
                             ChannelTransportFactory factory) {
        this.service = service;
        this.repo = repo;
        this.factory = factory;
    }

    @GetMapping
    public List<ChannelDTO> list() {
        return repo.findAll().stream().map(ChannelDTO::from).toList();
    }

    @GetMapping("/{id}")
    public ChannelDTO get(@PathVariable Long id) {
        return ChannelDTO.from(repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "channel not found: " + id)));
    }

    @PostMapping
    public ChannelDTO create(@Valid @RequestBody Channel ch) {
        return ChannelDTO.from(service.create(ch));
    }

    @PutMapping("/{id}")
    public ChannelDTO update(@PathVariable Long id, @Valid @RequestBody Channel ch) {
        if (!repo.existsById(id)) {
            throw new ResponseStatusException(NOT_FOUND, "channel not found: " + id);
        }
        return ChannelDTO.from(service.update(id, ch));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) {
            throw new ResponseStatusException(NOT_FOUND, "channel not found: " + id);
        }
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 同步测试连接。
     *
     * <p>优先复用 active transport（连接已建立），否则临时构造一个新 transport 仅调用
     * {@link Transport#testConnection}（不会调用 start，不污染 ChannelService.active map）。
     */
    @PostMapping("/{id}/test")
    public TestResult test(@PathVariable Long id) {
        Channel ch = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "channel not found: " + id));
        Transport t = service.activeTransport(id)
                .orElseGet(() -> factory.create(ch.getProtocol()));
        return t.testConnection(ch.getProtocolConfig());
    }

    /**
     * channels.csv + points.csv → ChannelDTO[] 解析端点。前端把用户上传的两份 CSV 喂给这里，
     * 拿到通道列表后再逐行调用 POST /channel 创建——复用现有"逐行状态表 + 同名 409 跳过"UX。
     *
     * <p>解析失败抛 IllegalArgumentException，由 GlobalExceptionHandler 映射为 400。
     */
    @PostMapping(value = "/parse-csv", consumes = "multipart/form-data")
    public List<ChannelDTO> parseCsv(@RequestPart("channels") MultipartFile channelsFile,
                                     @RequestPart("points") MultipartFile pointsFile)
            throws IOException {
        try (var chIn = channelsFile.getInputStream();
             var ptIn = pointsFile.getInputStream()) {
            return ChannelCsvParser.parse(chIn, ptIn).stream().map(ChannelDTO::from).toList();
        }
    }
}
