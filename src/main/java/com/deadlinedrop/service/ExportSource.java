package com.deadlinedrop.service;

import com.deadlinedrop.api.dto.DomainDtos.ExportRow;

import java.util.List;
import java.util.UUID;

/**
 * Port for querying submission export rows across ablation arms.
 */
public interface ExportSource {
    List<ExportRow> findExportRows(UUID assignmentId);
}
