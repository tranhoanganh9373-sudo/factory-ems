package com.ems.report.controller;

import com.ems.report.async.AsyncExportRunner;
import com.ems.report.async.FileTokenStore;
import com.ems.report.dto.ExportFormat;
import com.ems.report.dto.ExportPreset;
import com.ems.report.dto.FileTokenDTO;
import com.ems.report.dto.ReportExportRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReportExportControllerTest {

    @TempDir Path tmp;

    AsyncExportRunner exportRunner;
    FileTokenStore store;
    ReportExportController controller;

    @BeforeEach
    void setup() {
        exportRunner = mock(AsyncExportRunner.class);
        store = new FileTokenStore();
        controller = new ReportExportController(exportRunner, store, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static ReportExportRequest dailyReq() {
        return new ReportExportRequest(
                ExportFormat.CSV, ExportPreset.DAILY,
                new ReportExportRequest.Params(LocalDate.parse("2026-04-25"), null, null, null, 10L, List.of("ELEC")));
    }

    @Test
    void submitExport_returnsAcceptedWithToken_andDispatchesRunner() {
        ResponseEntity<FileTokenDTO> resp = controller.submitExport(dailyReq());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().token()).isNotBlank();
        assertThat(resp.getBody().status()).isEqualTo("PENDING");
        assertThat(resp.getBody().filename()).contains("daily-2026-04-25").endsWith(".csv");

        ArgumentCaptor<FileTokenStore.Entry> entryCap = ArgumentCaptor.forClass(FileTokenStore.Entry.class);
        ArgumentCaptor<ReportExportRequest> reqCap = ArgumentCaptor.forClass(ReportExportRequest.class);
        verify(exportRunner).run(entryCap.capture(), reqCap.capture());
        assertThat(entryCap.getValue().token).isEqualTo(resp.getBody().token());
        assertThat(reqCap.getValue().preset()).isEqualTo(ExportPreset.DAILY);
        assertThat(reqCap.getValue().format()).isEqualTo(ExportFormat.CSV);
    }

    @Test
    void submitExport_excelPreset_pickedUpInFilename() {
        var req = new ReportExportRequest(
                ExportFormat.EXCEL, ExportPreset.YEARLY,
                new ReportExportRequest.Params(null, null, java.time.Year.of(2026), null, null, null));
        ResponseEntity<FileTokenDTO> resp = controller.submitExport(req);
        assertThat(resp.getBody().filename()).contains("yearly-2026").endsWith(".xlsx");
    }

    @Test
    void submitExport_returnsBadRequest_whenBodyInvalid() {
        ResponseEntity<FileTokenDTO> resp = controller.submitExport(null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var missingFormat = new ReportExportRequest(null, ExportPreset.DAILY,
                new ReportExportRequest.Params(LocalDate.parse("2026-04-25"), null, null, null, null, null));
        assertThat(controller.submitExport(missingFormat).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void downloadExport_pendingOrRunning_returnsAccepted() {
        FileTokenStore.Entry e = store.create("x.csv");
        // PENDING
        var resp = controller.downloadExport(e.token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        // RUNNING
        e.status = FileTokenStore.Status.RUNNING;
        assertThat(controller.downloadExport(e.token).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void downloadExport_done_streamsFileAndEvictsToken() throws Exception {
        Path file = tmp.resolve("out.csv");
        Files.writeString(file, "hello");
        FileTokenStore.Entry e = store.create("out.csv");
        e.status = FileTokenStore.Status.DONE;
        e.file = file;
        e.bytes = Files.size(file);

        ResponseEntity<?> resp = controller.downloadExport(e.token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getFirst("Content-Disposition")).contains("out.csv");
        StreamingResponseBody body = (StreamingResponseBody) resp.getBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);
        assertThat(out.toString()).isEqualTo("hello");
        assertThat(store.find(e.token)).isEmpty();
    }

    @Test
    void downloadExport_failed_returnsGoneWithError() {
        FileTokenStore.Entry e = store.create("err.csv");
        e.status = FileTokenStore.Status.FAILED;
        e.error = "boom";

        ResponseEntity<?> resp = controller.downloadExport(e.token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(resp.getBody()).isInstanceOf(FileTokenDTO.class);
        assertThat(((FileTokenDTO) resp.getBody()).error()).isEqualTo("boom");
    }

    @Test
    void downloadExport_unknownToken_returnsGone() {
        ResponseEntity<?> resp = controller.downloadExport("does-not-exist");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void downloadExport_excelContentType_pickedFromFilename() throws Exception {
        Path file = tmp.resolve("out.xlsx");
        Files.writeString(file, "PKxxx");
        FileTokenStore.Entry e = store.create("out.xlsx");
        e.status = FileTokenStore.Status.DONE;
        e.file = file;

        ResponseEntity<?> resp = controller.downloadExport(e.token);
        assertThat(resp.getHeaders().getContentType().toString())
                .startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    void submitExport_runnerCanMutateEntry_statusReflected() {
        doAnswer(inv -> {
            FileTokenStore.Entry entry = inv.getArgument(0);
            entry.status = FileTokenStore.Status.RUNNING;
            return null;
        }).when(exportRunner).run(any(), any());

        ResponseEntity<FileTokenDTO> resp = controller.submitExport(dailyReq());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody().status()).isEqualTo("RUNNING");
    }
}
