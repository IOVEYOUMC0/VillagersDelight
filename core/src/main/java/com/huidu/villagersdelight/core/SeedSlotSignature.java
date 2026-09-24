package com.huidu.villagersdelight.core;

/**
 * Folds one inventory slot into the seed-slot cache signature used by the farm behaviour.
 *
 * The behaviour decodes the villager's seed slots once per scan and reuses that result until the signature
 * changes. The decode reads which items are held (their identity and their data components, which carry
 * CraftEngine custom ids) and whether a slot is empty, so exactly those are folded in. Stack counts are
 * deliberately excluded: eating or planting one seed must not invalidate the cache.
 */
public final class SeedSlotSignature {

    private SeedSlotSignature() {
    }

    /** Signature of an inventory before any slot is folded in. */
    public static long start() {
        return 1L;
    }

    /**
     * Folds one slot. itemHash and componentHash are ignored for an empty slot; a non-empty
     * slot is shifted by one so an item whose identity hashes to 0 cannot collide with an empty slot.
     */
    public static long fold(long signature, boolean empty, int itemHash, int componentHash) {
        long next = signature * 31L + (empty ? 0L : itemHash + 1L);
        return next * 31L + (empty ? 0L : componentHash);
    }
}
