package com.parcelrouting.api;

import com.parcelrouting.config.ConfigService;
import com.parcelrouting.config.ActiveRoutingConfig;
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
    public DraftResponse createDraft(@RequestBody CreateDraftRequest request, Authentication authentication) {
        RoutingConfig configuration = request.toRoutingConfig();
        RoutingConfigVersion draft = request.reason() == null
                ? configService.createDraft(configuration, authentication.getName())
                : configService.createDraft(configuration, authentication.getName(), request.reason());
        return new DraftResponse(draft.getVersion(), draft.getStatus(), configuration, draft.getReason());
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
    public DraftResponse activateDraft(
            @PathVariable Long version,
            @RequestBody(required = false) ActivateRequest request,
            Authentication authentication
    ) {
        RoutingConfigVersion activatedVersion = request == null
                ? configService.activate(version, authentication.getName())
                : configService.activate(version, authentication.getName(), request.acknowledgedRuleChanges());
        return new DraftResponse(
                activatedVersion.getVersion(),
                activatedVersion.getStatus(),
                null,
                activatedVersion.getReason()
        );
    }

    @PostMapping("/drafts/{version}/approve-material-change")
    @PreAuthorize("hasRole('ADMIN')")
    public DraftResponse approveMaterialChange(
            @PathVariable Long version,
            Authentication authentication
    ) {
        RoutingConfigVersion approvedVersion =
                configService.approveMaterialChange(version, authentication.getName());
        return new DraftResponse(
                approvedVersion.getVersion(),
                approvedVersion.getStatus(),
                null,
                approvedVersion.getReason()
        );
    }

    @GetMapping("/active")
    @PreAuthorize("hasRole('ADMIN')")
    public ActiveConfigResponse activeConfiguration() {
        ActiveRoutingConfig active = configService.getActiveConfigWithVersion();
        return new ActiveConfigResponse(active.version(), active.routingConfig(), active.reason());
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
                null,
                activatedVersion.getReason()
        );
    }

    public record DraftResponse(
            int version, ConfigVersionStatus status, RoutingConfig configuration, String reason
    ) {
    }

    public record ActiveConfigResponse(int version, RoutingConfig configuration, String reason) {
        public ActiveConfigResponse(int version, RoutingConfig configuration) {
            this(version, configuration, null);
        }
    }

    public record ActivateRequest(List<String> acknowledgedRuleChanges) {
    }

    public record CreateDraftRequest(int insuranceThresholdEur, List<com.parcelrouting.routing.Rule> rules, String reason) {
        public RoutingConfig toRoutingConfig() {
            return new RoutingConfig(insuranceThresholdEur, rules);
        }
    }
}
