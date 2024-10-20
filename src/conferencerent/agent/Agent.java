package conferencerent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rabbitmq.client.*;
import conferencerent.model.ClientRequestMessage;
import conferencerent.model.ClientRequestType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Agent {
    private static final String EXCHANGE_CLIENT = "client_exchange";
    private static final String CLIENT_AGENT_QUEUE = "client_to_agent_queue";
    private static final String AGENT_CLIENT_QUEUE = "agent_to_client_queue";

    // Store confirmed reservations per client
    private static final Map<String, List<ClientRequestMessage>> reservations = new HashMap<>();
    // Temporarily store unconfirmed reservations
    private static final Map<String, ClientRequestMessage> unconfirmedReservations = new HashMap<>();
    private static Channel channel;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        new Agent().run();
    }

    public void run() throws Exception {
        objectMapper.configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, true);
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setUsername("guest");
        factory.setPassword("guest");
        factory.setPort(5672);
        Connection connection = factory.newConnection();
        channel = connection.createChannel();

        channel.exchangeDeclare(EXCHANGE_CLIENT, BuiltinExchangeType.DIRECT);
        channel.queueDeclare(CLIENT_AGENT_QUEUE, false, false, false, null);
        channel.queueDeclare(AGENT_CLIENT_QUEUE, false, false, false, null);

        channel.queueBind(CLIENT_AGENT_QUEUE, EXCHANGE_CLIENT, "client_to_agent");
        channel.queueBind(AGENT_CLIENT_QUEUE, EXCHANGE_CLIENT, "agent_to_client");

        listenForClientRequests();
    }

    private void listenForClientRequests() throws Exception {
        channel.basicConsume(CLIENT_AGENT_QUEUE, true, (consumerTag, delivery) -> {
            String jsonRequest = new String(delivery.getBody(), StandardCharsets.UTF_8);
            ClientRequestMessage requestMessage;

            try {
                requestMessage = objectMapper.readValue(jsonRequest, ClientRequestMessage.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse client request", e);
            }
            System.out.println("Received request of type: " + requestMessage.getType());

            switch (requestMessage.getType()) {
                case LIST_BUILDINGS -> sendBuildingList(requestMessage.getClientId());
                case BOOK -> sendReservationNumber(requestMessage);
                case CONFIRM -> confirmBooking(requestMessage);
                case LIST_RESERVATIONS -> listReservations(requestMessage.getClientId());
                case CANCEL -> cancelReservation(requestMessage);
                default -> System.out.println("Unknown request type.");
            }
        }, consumerTag -> {});
    }

    private void sendBuildingList(String clientId) throws IOException {
        ClientRequestMessage responseMessage = new ClientRequestMessage(clientId, ClientRequestType.LIST_BUILDINGS);
        Map<String, ArrayList<Integer>> bookingRooms = new HashMap<>();
        ArrayList<Integer> roomsA = new ArrayList<>(Arrays.asList(101, 102));
        bookingRooms.put("Building A", roomsA);
        ArrayList<Integer> roomsB = new ArrayList<>(Arrays.asList(201, 202));
        bookingRooms.put("Building B", roomsB);
        responseMessage.setBuildings(bookingRooms);
        sendResponse(responseMessage);
    }

    private void sendReservationNumber(ClientRequestMessage requestMessage) throws IOException {
        // Generate a reservation number
        String reservationNumber = UUID.randomUUID().toString();

        // Store the unconfirmed reservation
        requestMessage.setReservationNumber(reservationNumber);
        unconfirmedReservations.put(reservationNumber, requestMessage);

        // Prepare response message
        ClientRequestMessage responseMessage = new ClientRequestMessage(requestMessage.getClientId(), ClientRequestType.BOOK);
        responseMessage.setReservationNumber(reservationNumber);
        responseMessage.setBuildings(requestMessage.getBuildings());

        // Send response back to client
        sendResponse(responseMessage);
    }

    private void confirmBooking(ClientRequestMessage requestMessage) throws IOException {
        String reservationNumber = requestMessage.getReservationNumber();
        String clientId = requestMessage.getClientId();

        // Retrieve the unconfirmed reservation
        ClientRequestMessage unconfirmed = unconfirmedReservations.remove(reservationNumber);

        if (unconfirmed != null && unconfirmed.getClientId().equals(clientId)) {
            // Add to confirmed reservations
            reservations.computeIfAbsent(clientId, k -> new ArrayList<>()).add(unconfirmed);

            // Send confirmation to client
            ClientRequestMessage responseMessage = new ClientRequestMessage(clientId, ClientRequestType.CONFIRM);
            responseMessage.setReservationNumber(reservationNumber);
            sendResponse(responseMessage);
        } else {
            // Send error message to client
            ClientRequestMessage errorMessage = new ClientRequestMessage(clientId, ClientRequestType.ERROR);
            errorMessage.setErrorMessage("Invalid reservation number or reservation has already been confirmed.");
            sendResponse(errorMessage);
        }
    }

    private void listReservations(String clientId) throws IOException {
        List<ClientRequestMessage> clientReservations = reservations.get(clientId);

        ClientRequestMessage responseMessage = new ClientRequestMessage(clientId, ClientRequestType.LIST_RESERVATIONS);
        if (clientReservations != null && !clientReservations.isEmpty()) {
            // Collect reservation numbers
            StringBuilder reservationNumbers = new StringBuilder();
            for (ClientRequestMessage reservation : clientReservations) {
                if (reservationNumbers.length() > 0) {
                    reservationNumbers.append(",");
                }
                reservationNumbers.append(reservation.getReservationNumber());
            }
            responseMessage.setReservationNumber(reservationNumbers.toString());
        } else {
            responseMessage.setReservationNumber("");
        }

        sendResponse(responseMessage);
    }

    private void cancelReservation(ClientRequestMessage requestMessage) throws IOException {
        String reservationNumber = requestMessage.getReservationNumber();
        String clientId = requestMessage.getClientId();

        List<ClientRequestMessage> clientReservations = reservations.get(clientId);
        if (clientReservations != null) {
            ClientRequestMessage toRemove = null;
            for (ClientRequestMessage reservation : clientReservations) {
                if (reservation.getReservationNumber().equals(reservationNumber)) {
                    toRemove = reservation;
                    break;
                }
            }
            if (toRemove != null) {
                clientReservations.remove(toRemove);
                // Send cancellation confirmation
                ClientRequestMessage responseMessage = new ClientRequestMessage(clientId, ClientRequestType.CANCEL);
                responseMessage.setReservationNumber(reservationNumber);
                sendResponse(responseMessage);
                return;
            }
        }

        // Send error message
        ClientRequestMessage errorMessage = new ClientRequestMessage(clientId, ClientRequestType.ERROR);
        errorMessage.setErrorMessage("Reservation not found.");
        sendResponse(errorMessage);
    }

    private void sendResponse(ClientRequestMessage responseMessage) throws IOException {
        String message = objectMapper.writeValueAsString(responseMessage);
        channel.basicPublish(EXCHANGE_CLIENT, "agent_to_client", null, message.getBytes(StandardCharsets.UTF_8));
    }
}
