package org.anteroom.room;

/**
 * Участие в комнате. Роль здесь есть, но в ленте она не показывается: роль нужна только
 * в момент действия — выпуск инвайта, исключение участника, — и там её место.
 */
public record Member(String pubkeySign, String role, int card, String invitedBy, long joinedAt) {
}
