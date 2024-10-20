package conferencerent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClientRequestMessage {
    private String clientId;
    private ClientRequestType type;
    private String building;
    private ArrayList<Integer> rooms;
    private String reservationNumber;

    private String errorMessage;

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public ClientRequestMessage() {
        this.rooms = new ArrayList<>();
    }

    public ClientRequestMessage(String clientId, ClientRequestType type) {
        this.clientId = clientId;
        this.type = type;
        this.rooms = new ArrayList<>();
    }

    public ClientRequestType getType() {
        return type;
    }

    public void setType(ClientRequestType type) {
        this.type = type;
    }

    public String getClientId(){
        return clientId;
    }

    public String getBuilding() {
        return building;
    }

    public void setBuilding(String building) {
        this.building = building;
    }

    public ArrayList<Integer> getRooms() {
        return this.rooms;
    }

    public void setRoom(ArrayList<Integer> rooms) {
        this.rooms = rooms;
    }

    public String getReservationNumber() {
        return reservationNumber;
    }

    public void setReservationNumber(String reservationNumber) {
        this.reservationNumber = reservationNumber;
    }
}