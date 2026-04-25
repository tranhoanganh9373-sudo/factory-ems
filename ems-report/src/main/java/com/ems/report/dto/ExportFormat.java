package com.ems.report.dto;

/** 异步导出输出格式（决定 exporter + 文件扩展名 + Content-Type）。 */
public enum ExportFormat {
    CSV("csv", "text/csv; charset=UTF-8"),
    EXCEL("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("pdf", "application/pdf");

    private final String ext;
    private final String contentType;

    ExportFormat(String ext, String contentType) {
        this.ext = ext;
        this.contentType = contentType;
    }

    public String ext() { return ext; }
    public String contentType() { return contentType; }
}
