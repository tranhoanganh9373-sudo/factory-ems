package com.ems.production.dto;

import java.util.List;

public record BulkImportResult(int total, int succeeded, List<BulkImportError> errors) {}
