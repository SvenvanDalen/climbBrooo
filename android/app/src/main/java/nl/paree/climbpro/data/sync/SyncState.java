package nl.paree.climbpro.data.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class SyncState {

    public String routeId;
    public String lastSyncedHash;
    public long lastSyncedAtMs;
    public int retryCount;

    public enum Status { PENDING, SYNCED, FAILED }
    public Status status = Status.PENDING;
}
