package com.expensetracker.integration;

import com.expensetracker.auth.CurrentUser;
import com.expensetracker.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class IntegrationController {

    public record ConnectRequest(UUID defaultAccountId) {
    }

    private final IntegrationService integrations;
    private final CsvImportService csvImport;

    public IntegrationController(IntegrationService integrations, CsvImportService csvImport) {
        this.integrations = integrations;
        this.csvImport = csvImport;
    }

    @GetMapping("/integrations")
    public List<IntegrationService.CatalogueEntry> catalogue() {
        return integrations.catalogue(CurrentUser.id());
    }

    @PostMapping("/integrations/{provider}/connect")
    @ResponseStatus(HttpStatus.CREATED)
    public IntegrationService.ConnectResult connect(@PathVariable String provider, @RequestBody(required = false) ConnectRequest req) {
        return integrations.connect(CurrentUser.id(), provider, req == null ? null : req.defaultAccountId());
    }

    @PutMapping("/integrations/connections/{id}")
    public IntegrationService.ConnectionDto update(@PathVariable UUID id, @RequestBody ConnectRequest req) {
        return integrations.update(CurrentUser.id(), id, req.defaultAccountId());
    }

    @PostMapping("/integrations/connections/{id}/rotate-token")
    public IntegrationService.ConnectResult rotate(@PathVariable UUID id) {
        return integrations.rotateToken(CurrentUser.id(), id);
    }

    @PostMapping("/integrations/connections/{id}/disconnect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable UUID id) {
        integrations.disconnect(CurrentUser.id(), id);
    }

    @GetMapping("/integrations/history")
    public List<IntegrationService.SyncDto> history(@RequestParam(required = false) UUID integrationId) {
        return integrations.history(CurrentUser.id(), integrationId);
    }

    @PostMapping(value = "/imports/csv", consumes = "multipart/form-data")
    public CsvImportService.ImportResult importCsv(@RequestParam UUID accountId, @RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw ApiException.badRequest("The file is empty");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".csv") && !name.endsWith(".txt")) {
            throw ApiException.badRequest("Only CSV statements are supported right now. Export your statement as CSV/Delimited.");
        }
        return csvImport.importStatement(CurrentUser.id(), accountId, new String(file.getBytes(), StandardCharsets.UTF_8));
    }
}
