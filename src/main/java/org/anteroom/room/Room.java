package org.anteroom.room;

public record Room(String id, int keyEpoch, long defaultTtl, long maxTtl, int seatsTaken,
                   boolean directAllowed, long createdAt) {

    /** Колода роздана целиком — комната больше никого не принимает. */
    public boolean deckSpent() {
        return seatsTaken >= CardDealer.DECK_SIZE;
    }
}
