package conferencerent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

/**
 * Represents a message exchanged between the client and the agent.
 * Contains information about buildings, reservations, and error messages.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClientMessage {
    private String clientId;
    private MessageType type;
    @JsonProperty("buildings")
    private Map<String, ArrayList<Integer>> buildings = new HashMap<>();
    private String reservationNumber;
    private String errorMessage;

    /**
     * Default constructor.
     */
    public ClientMessage() {
    }

    /**
     * Constructs a ClientMessage with a client ID and message type.
     *
     * @param clientId the client's unique identifier
     * @param type     the type of the message
     */
    public ClientMessage(String clientId, MessageType type) {
        this.clientId = clientId;
        this.type = type;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getClientId() {
        return clientId;
    }

    public Map<String, ArrayList<Integer>> getBuildings() {
        return buildings;
    }

    public void setBuildings(Map<String, ArrayList<Integer>> buildings) {
        this.buildings = buildings;
    }

    public ArrayList<String> getBuildingNames() {
        return new ArrayList<>(buildings.keySet());
    }

    public ArrayList<Integer> getRooms(String building) {
        return buildings.get(building);
    }

    public String getReservationNumber() {
        return reservationNumber;
    }

    public void setReservationNumber(String reservationNumber) {
        this.reservationNumber = reservationNumber;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
