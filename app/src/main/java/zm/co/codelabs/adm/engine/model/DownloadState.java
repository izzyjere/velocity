package zm.co.codelabs.adm.engine.model;

import java.util.EnumSet;
import java.util.Map;

public enum DownloadState {
    NEW, PROBING, QUEUED, RUNNING, PAUSING, PAUSED, VERIFYING, RETRY_WAIT, COMPLETED, FAILED, CANCELED;

    private static final Map<DownloadState, EnumSet<DownloadState>> ALLOWED = Map.ofEntries(
            Map.entry(NEW, EnumSet.of(PROBING, QUEUED, CANCELED)),
            Map.entry(PROBING, EnumSet.of(QUEUED, RETRY_WAIT, FAILED, CANCELED)),
            Map.entry(QUEUED, EnumSet.of(RUNNING, PAUSED, FAILED, CANCELED)),
            Map.entry(RUNNING, EnumSet.of(PAUSING, VERIFYING, RETRY_WAIT, FAILED, CANCELED)),
            Map.entry(PAUSING, EnumSet.of(PAUSED, FAILED, CANCELED)),
            Map.entry(PAUSED, EnumSet.of(QUEUED, CANCELED)),
            Map.entry(VERIFYING, EnumSet.of(COMPLETED, RETRY_WAIT, FAILED, CANCELED)),
            Map.entry(RETRY_WAIT, EnumSet.of(QUEUED, PAUSED, FAILED, CANCELED)),
            Map.entry(FAILED, EnumSet.of(QUEUED, CANCELED)),
            Map.entry(COMPLETED, EnumSet.noneOf(DownloadState.class)),
            Map.entry(CANCELED, EnumSet.noneOf(DownloadState.class)));

    public boolean canTransitionTo(DownloadState next) { return ALLOWED.get(this).contains(next); }
}
