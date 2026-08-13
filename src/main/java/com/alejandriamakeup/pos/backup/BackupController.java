package com.alejandriamakeup.pos.backup;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/backup")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> respaldarAhora() throws SQLException {
        Path archivo = backupService.ejecutar();
        return ResponseEntity.ok(Map.of("archivo", archivo.toString()));
    }
}
