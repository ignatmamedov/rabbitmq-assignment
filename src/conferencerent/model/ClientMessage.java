package conferencerent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClientMessage {
    private String clientId;
    private MessageType type;
    @JsonProperty("buildings")
    private Map<String, ArrayList<Integer>> buildings;
    private String reservationNumber;

    private String errorMessage;

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public ClientMessage() {
        this.buildings = new HashMap<>();
    }

    public ClientMessage(String clientId, MessageType type) {
        this.clientId = clientId;
        this.type = type;
        this.buildings = new HashMap<>();
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getClientId(){
        return clientId;
    }

    public ArrayList<Integer> getRooms(String building) {
        return this.buildings.get(building);
    }

    public Map<String, ArrayList<Integer>> getBuildings() {
        return buildings;
    }

    public void setBuildings(Map<String, ArrayList<Integer>> buildings){
        this.buildings = buildings;
    }

    public ArrayList<String> getBuildingNames() {
        return new ArrayList<>(buildings.keySet());
    }

    public String getReservationNumber() {
        return reservationNumber;
    }

    public void setReservationNumber(String reservationNumber) {
        this.reservationNumber = reservationNumber;
    }
}