package com.ielop.platform.detection;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * {@code app.rules.*}: rule switches and thresholds (section 10). Changing a threshold
 * never deletes earlier findings.
 */
@ConfigurationProperties(prefix = "app.rules")
public class RulesProperties {

    /** Rules that do not run; R02 is off by default (owner decision, docs/DECISIONS.md). */
    private Set<String> disabled = Set.of("R02");
    private String businessTimezone = "UTC";
    private int offHoursStart = 0;
    private int offHoursEnd = 5;
    private int stuckAfterMinutes = 240;
    private int failedLoginThreshold = 5;
    private int failedLoginWindowMinutes = 10;
    private long slowStageFloorMs = 15_000;
    private int slowStageBaselineDays = 7;
    /** R09 needs at least this many completed instances of the stage in the baseline window; fewer means no baseline yet. */
    private int slowStageMinSamples = 30;
    private int duplicateWindowSeconds = 60;
    private int authzDeniedThreshold = 5;
    private int authzDistinctResources = 3;
    private int authzWindowMinutes = 10;
    private int scannerRequestThreshold = 100;
    private int scannerWindowMinutes = 5;
    private double scannerRejectedShare = 0.6;
    private List<String> scannerUserAgents = List.of("sqlmap", "nikto", "masscan", "nmap", "zgrab", "python-requests");
    private int scanLookbackHours = 24;
    private int scanIntervalMinutes = 5;
    /** Run the scheduled scan and the job worker; tests switch it off and drive the worker directly. */
    private boolean scheduleEnabled = true;
    /** Event actions of a failed sign-in (R07) and of a permission denial (R14), from the log contract (section 3). */
    private List<String> failedLoginActions = List.of("LOGIN_FAILED", "MFA_FAILED");
    private List<String> authzDeniedActions = List.of("AUTHZ_DENIED");
    /** HTTP statuses counted as rejected for R15. */
    private List<Integer> scannerRejectedStatuses = List.of(401, 403, 404);
    /** Evidence lines kept per scan finding (section 10.1). */
    private int evidenceSample = 50;
    /** Upper bound of lines one scan rule reads from Elasticsearch. */
    private int scanLineCap = 200_000;

    public boolean enabled(String ruleId) {
        return !disabled.contains(ruleId);
    }

    public ZoneId zone() {
        return ZoneId.of(businessTimezone);
    }

    public Set<String> getDisabled() { return disabled; }
    public void setDisabled(Set<String> v) { disabled = v; }
    public String getBusinessTimezone() { return businessTimezone; }
    public void setBusinessTimezone(String v) { businessTimezone = v; }
    public int getOffHoursStart() { return offHoursStart; }
    public void setOffHoursStart(int v) { offHoursStart = v; }
    public int getOffHoursEnd() { return offHoursEnd; }
    public void setOffHoursEnd(int v) { offHoursEnd = v; }
    public int getStuckAfterMinutes() { return stuckAfterMinutes; }
    public void setStuckAfterMinutes(int v) { stuckAfterMinutes = v; }
    public int getFailedLoginThreshold() { return failedLoginThreshold; }
    public void setFailedLoginThreshold(int v) { failedLoginThreshold = v; }
    public int getFailedLoginWindowMinutes() { return failedLoginWindowMinutes; }
    public void setFailedLoginWindowMinutes(int v) { failedLoginWindowMinutes = v; }
    public long getSlowStageFloorMs() { return slowStageFloorMs; }
    public void setSlowStageFloorMs(long v) { slowStageFloorMs = v; }
    public int getSlowStageBaselineDays() { return slowStageBaselineDays; }
    public int getSlowStageMinSamples() { return slowStageMinSamples; }
    public void setSlowStageMinSamples(int v) { slowStageMinSamples = v; }
    public void setSlowStageBaselineDays(int v) { slowStageBaselineDays = v; }
    public int getDuplicateWindowSeconds() { return duplicateWindowSeconds; }
    public void setDuplicateWindowSeconds(int v) { duplicateWindowSeconds = v; }
    public int getAuthzDeniedThreshold() { return authzDeniedThreshold; }
    public void setAuthzDeniedThreshold(int v) { authzDeniedThreshold = v; }
    public int getAuthzDistinctResources() { return authzDistinctResources; }
    public void setAuthzDistinctResources(int v) { authzDistinctResources = v; }
    public int getAuthzWindowMinutes() { return authzWindowMinutes; }
    public void setAuthzWindowMinutes(int v) { authzWindowMinutes = v; }
    public int getScannerRequestThreshold() { return scannerRequestThreshold; }
    public void setScannerRequestThreshold(int v) { scannerRequestThreshold = v; }
    public int getScannerWindowMinutes() { return scannerWindowMinutes; }
    public void setScannerWindowMinutes(int v) { scannerWindowMinutes = v; }
    public double getScannerRejectedShare() { return scannerRejectedShare; }
    public void setScannerRejectedShare(double v) { scannerRejectedShare = v; }
    public List<String> getScannerUserAgents() { return scannerUserAgents; }
    public void setScannerUserAgents(List<String> v) { scannerUserAgents = v; }
    public int getScanIntervalMinutes() { return scanIntervalMinutes; }
    public void setScanIntervalMinutes(int v) { scanIntervalMinutes = v; }
    public boolean isScheduleEnabled() { return scheduleEnabled; }
    public void setScheduleEnabled(boolean v) { scheduleEnabled = v; }
    public List<String> getFailedLoginActions() { return failedLoginActions; }
    public void setFailedLoginActions(List<String> v) { failedLoginActions = v; }
    public List<String> getAuthzDeniedActions() { return authzDeniedActions; }
    public void setAuthzDeniedActions(List<String> v) { authzDeniedActions = v; }
    public List<Integer> getScannerRejectedStatuses() { return scannerRejectedStatuses; }
    public void setScannerRejectedStatuses(List<Integer> v) { scannerRejectedStatuses = v; }
    public int getEvidenceSample() { return evidenceSample; }
    public void setEvidenceSample(int v) { evidenceSample = v; }
    public int getScanLineCap() { return scanLineCap; }
    public void setScanLineCap(int v) { scanLineCap = v; }
    public int getScanLookbackHours() { return scanLookbackHours; }
    public void setScanLookbackHours(int v) { scanLookbackHours = v; }
}
