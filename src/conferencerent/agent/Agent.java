package conferencerent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rabbitmq.client.*;
import conferencerent.model.ClientRequestMessage;
import conferencerent.model.ClientRequestType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Agent {
    private static final String EXCHANGE_CLIENT = "client_exchange";
    private static final String CLIENT_AGENT_QUEUE = "client_to_agent_queue";
    private static final String AGENT_CLIENT_QUEUE = "agent_to_client_queue";
    private static final Map<String, String> reservations = new HashMap<>();
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

        // Привязываем очередь для запросов с ключом "client_to_agent"
        channel.queueBind(CLIENT_AGENT_QUEUE, EXCHANGE_CLIENT, "client_to_agent");
        // Привязываем очередь для ответов с ключом "agent_to_client"
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
            System.out.println(requestMessage.getType());

            switch (requestMessage.getType()) {
                case LIST_BUILDINGS -> sendBuildingList(requestMessage.getClientId());
                case BOOK -> sendReservationNumber(requestMessage);  // Send reservation number, wait for confirmation
                case CONFIRM -> confirmBooking(requestMessage);
                case LIST_RESERVATIONS -> listReservations(requestMessage.getClientId());
                case CANCEL -> cancelReservation(requestMessage);
                default -> System.out.println("Unknown request type.");
            }
        }, consumerTag -> {});
    }

    private void sendBuildingList(String clientId) throws IOException {
        String buildingsInfo = "Building A: 101, 102\nBuilding B: 201, 202";

        ClientRequestMessage responseMessage = new ClientRequestMessage(clientId, ClientRequestType.LIST_BUILDINGS);
        responseMessage.setBuilding(buildingsInfo);

        sendResponse(responseMessage);
    }

    private void sendReservationNumber(ClientRequestMessage requestMessage) throws IOException {
        String reservationId = UUID.randomUUID().toString();
        reservations.put(reservationId, "Building: " + requestMessage.getBuilding() + ", Rooms: " + requestMessage.getRooms());

        ClientRequestMessage responseMessage = new ClientRequestMessage(requestMessage.getClientId(), ClientRequestType.BOOK);
        responseMessage.setReservationNumber(reservationId);
        sendResponse(responseMessage);
    }

    private void confirmBooking(ClientRequestMessage requestMessage) throws IOException {
        String reservationId = requestMessage.getReservationNumber();
        if (reservations.containsKey(reservationId)) {
            ClientRequestMessage responseMessage = new ClientRequestMessage(requestMessage.getClientId(), ClientRequestType.CONFIRM);
            responseMessage.setReservationNumber(reservationId);
            sendResponse(responseMessage);
        }
    }

    private void listReservations(String clientId) throws IOException {
        StringBuilder reservationList = new StringBuilder();

        for (Map.Entry<String, String> entry : reservations.entrySet()) {
            reservationList.append("Reservation ID: ").append(entry.getKey()).append(" - ").append(entry.getValue()).append("\n");
        }

        ClientRequestMessage responseMessage = new ClientRequestMessage(clientId, ClientRequestType.LIST_RESERVATIONS);
        responseMessage.setReservationNumber(reservationList.toString());
        sendResponse(responseMessage);
    }

    private void cancelReservation(ClientRequestMessage requestMessage) throws IOException {
        String reservationId = requestMessage.getReservationNumber();
        ClientRequestMessage responseMessage;
        if (reservations.containsKey(reservationId)) {
            reservations.remove(reservationId);
            responseMessage = new ClientRequestMessage(requestMessage.getClientId(), ClientRequestType.CANCEL);
            responseMessage.setReservationNumber(reservationId);
        } else {
            responseMessage = new ClientRequestMessage(requestMessage.getClientId(), ClientRequestType.ERROR);
            responseMessage.setErrorMessage("Reservation ID not found: " + reservationId);
        }
        sendResponse(responseMessage);
    }

    private void sendResponse(ClientRequestMessage responseMessage) throws IOException {
        String message = objectMapper.writeValueAsString(responseMessage);
        channel.basicPublish(EXCHANGE_CLIENT, "agent_to_client", null, message.getBytes(StandardCharsets.UTF_8));
    }
}
