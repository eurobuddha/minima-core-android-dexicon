package com.eurobuddha.atomix;

/**
 * DEXICON-OWNED SHIM (not vendored, not an Activity): the vendored {@link com.eurobuddha.atomix.swap.SwapEngine} reads
 * AtomiX's host flag {@code MainActivity.SWEEP_ACTIVE} (a SELL sweep owns the Minima coin pool — no consolidation may
 * start). Dexicon's DOORS set it while a door SELL sweep is locking parts (see com.eurobuddha.dexicon.doors.DoorHost).
 */
public final class MainActivity {
    private MainActivity() {}
    public static volatile boolean SWEEP_ACTIVE = false;
}
