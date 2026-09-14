package com.parcelrouting.api;

import com.parcelrouting.config.ConfigService;
import com.parcelrouting.config.ConfigVersionStatus;
import com.parcelrouting.config.RoutingConfigVersion;
import com.parcelrouting.routing.RoutingConfig;
import com.parcelrouting.routing.DryRunSimulator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @PostMapping("/drafts")
    @PreAuthorize("hasRole('ADMIN')")
    public DraftResponse createDraft(@RequestBody RoutingConfig configuration, Authentication authentication) {
        RoutingConfigVersion draft = configService.createDraft(configuration, authentication.getName());
        return new DraftResponse(draft.getVersion(), draft.getStatus(), configuration);
    }

    @PostMapping("/drafts/{version}/validate")
    @PreAuthorize("hasRole('ADMIN')")
    public ConfigService.DraftValidationResult validateDraft(@PathVariable int version) {
        return configService.validateDraft(version);
    }

    @PostMapping("/drafts/{version}/dry-run")
    @PreAuthorize("hasRole('ADMIN')")
    public DryRunSimulator.DryRunResult dryRunDraft(@PathVariable int version) {
        return configService.dryRun(version);
    }

    @PostMapping("/drafts/{version}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public DraftResponse activateDraft(@PathVariable Long version, Authentication authentication) {
        RoutingConfigVersion activatedVersion = configService.activate(version, authentication.getName());
        return new DraftResponse(
                activatedVersion.getVersion(),
                activatedVersion.getStatus(),
                null
        );
    }

    @GetMapping("/history")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ConfigService.ConfigHistoryEntry> history() {
        return configService.history();
    }

    @PostMapping("/{version}/rollback")
    @PreAuthorize("hasRole('ADMIN')")
    public DraftResponse rollback(@PathVariable Long version, Authentication authentication) {
        RoutingConfigVersion activatedVersion = configService.rollback(version, authentication.getName());
        return new DraftResponse(
                activatedVersion.getVersion(),
                activatedVersion.getStatus(),
                null
        );
    }

    public record DraftResponse(int version, ConfigVersionStatus status, RoutingConfig configuration) {
    }
}
