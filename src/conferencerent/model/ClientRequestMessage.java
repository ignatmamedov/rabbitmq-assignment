package conferencerent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClientRequestMessage {
    private String clientId;
    private ClientRequestType type;
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

    public ClientRequestMessage() {
        this.buildings = new HashMap<>();
    }

    public ClientRequestMessage(String clientId, ClientRequestType type) {
        this.clientId = clientId;
        this.type = type;
        this.buildings = new HashMap<>();
    }

    public ClientRequestType getType() {
        return type;
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