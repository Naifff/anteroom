package org.anteroom.file;

public record StoredFile(String id, String roomId, long sizeBytes, long expiresAt) {
}
