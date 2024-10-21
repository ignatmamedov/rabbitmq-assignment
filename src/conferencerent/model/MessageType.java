package conferencerent.model;

/**
 * Enumerates the different types of messages that can be exchanged
 * between clients, agents, and buildings.
 */
public enum MessageType {
    BUILDING_STATUS,
    LIST_BUILDINGS,
    BOOK,
    LIST_RESERVATIONS,
    CONFIRM,
    CANCEL,
    ERROR,
    REQUEST_BUILDING_STATUS
}