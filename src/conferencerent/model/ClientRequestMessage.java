package conferencerent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClientRequestMessage {
    private String clientId;
    private ClientRequestType type;
    private String building;
    private Integer room;
    private String reservationNumber;

    public ClientRequestMessage() {}

    public ClientRequestMessage(String clientId, ClientRequestType type) {
        this.clientId = clientId;
        this.type = type;
    }

    public ClientRequestType getType() {
        return type;
    }

    public void setType(ClientRequestType type) {
        this.type = type;
    }

    public String getBuilding() {
        return building;
    }

    public void setBuilding(String building) {
        this.building = building;
    }

    public Integer getRoom() {
        return room;
    }

    public void setRoom(Integer room) {
        this.room = room;
    }

    public String getReservationNumber() {
        return reservationNumber;
    }

    public void setReservationNumber(String reservationNumber) {
        this.reservationNumber = reservationNumber;
    }
}