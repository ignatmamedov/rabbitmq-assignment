package conferencerent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Represents a message exchanged between the building and the agent.
 * Contains information about building status, available rooms, and requested rooms.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BuildingMessage {
    private MessageType type;
    private String buildingName;
    private String errorMessage;
    private List<Integer> availableRooms;
    private List<Integer> requestedRooms;

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getBuildingName() {
        return buildingName;
    }

    public void setBuildingName(String buildingName) {
        this.buildingName = buildingName;
    }

    public List<Integer> getAvailableRooms() {
        return availableRooms;
    }

    public void setAvailableRooms(List<Integer> availableRooms) {
        this.availableRooms = availableRooms;
    }

    public List<Integer> getRequestedRooms() {
        return requestedRooms;
    }

    public void setRequestedRooms(List<Integer> requestedRooms) {
        this.requestedRooms = requestedRooms;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
