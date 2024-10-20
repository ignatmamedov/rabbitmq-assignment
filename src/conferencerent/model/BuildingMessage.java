package conferencerent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BuildingMessage {
    private MessageType type;
    private String buildingName;
    private List<Integer> availableRooms;
    private List<Integer> requestedRooms;
    private boolean success;
    private String errorMessage;

    public BuildingMessage(){}

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

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
