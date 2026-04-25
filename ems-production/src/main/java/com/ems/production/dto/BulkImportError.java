package com.ems.production.dto;

public record BulkImportError(int rowNumber, String reason) {}
