package conferencerent.agent;

import com.rabbitmq.client.*;
import conferencerent.model.ClientMessage;
import conferencerent.model.BuildingMessage;
import conferencerent.model.MessageType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Agent {
    private static final String EXCHANGE_CLIENT = "client_exchange";
    private static final String EXCHANGE_DIRECT = "direct_exchange";
    private static final String EXCHANGE_FANOUT = "building_announce_exchange";
    private static final String CLIENT_AGENT_QUEUE = "client_to_agent_queue";
    private static final String AGENT_QUEUE_NAME = "agent_to_building_queue_" + UUID.randomUUID();

    private static final Map<String, List<ClientMessage>> reservations = new ConcurrentHashMap<>();
    private static final Map<String, ClientMessage> unconfirmedReservations = new ConcurrentHashMap<>();
    private static final Map<String, Set<Integer>> availableRooms = new ConcurrentHashMap<>();

    private static final Map<String, Long> buildingTimestamps = new ConcurrentHashMap<>();

    private static final int TIMEOUT = 1000;

    private static Channel channel;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        new Agent().run();
    }

    public void run() throws Exception {
        objectMapper.configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, true);
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        Connection connection = factory.newConnection();
        channel = connection.createChannel();

        channel.exchangeDeclare(EXCHANGE_CLIENT, BuiltinExchangeType.DIRECT);
        channel.exchangeDeclare(EXCHANGE_FANOUT, BuiltinExchangeType.FANOUT);

        channel.queueDeclare(CLIENT_AGENT_QUEUE, false, false, false, null);
        channel.queueBind(CLIENT_AGENT_QUEUE, EXCHANGE_CLIENT, "client_to_agent");

        channel.queueDeclare(AGENT_QUEUE_NAME, false, false, false, null);

        channel.queueBind(AGENT_QUEUE_NAME, EXCHANGE_DIRECT, "agent_building_interaction");
        channel.queueBind(AGENT_QUEUE_NAME, EXCHANGE_FANOUT, "");

        requestBuildingStatusFromAll();

        listenForClientRequests();

        listenForBuildingMessages();

        removeInactiveBuildings();
    }

    private void requestBuildingStatusFromAll() throws IOException {
        BuildingMessage request = new BuildingMessage();
        request.setType(MessageType.REQUEST_BUILDING_STATUS);
        String message = objectMapper.writeValueAsString(request);

        channel.basicPublish(EXCHANGE_FANOUT, "", null, message.getBytes(StandardCharsets.UTF_8));
        System.out.println("Sent REQUEST_BUILDING_STATUS to all buildings.");
    }

    private void listenForClientRequests() throws Exception {
        channel.basicConsume(CLIENT_AGENT_QUEUE, true, (consumerTag, delivery) -> {
            String jsonRequest = new String(delivery.getBody(), StandardCharsets.UTF_8);
            ClientMessage requestMessage;

            try {
                requestMessage = objectMapper.readValue(jsonRequest, ClientMessage.class);
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }

            System.out.println("Received client request of type: " + requestMessage.getType());

            switch (requestMessage.getType()) {
                case LIST_BUILDINGS -> sendBuildingList(requestMessage.getClientId());
                case BOOK -> sendReservationNumber(requestMessage);
                case CONFIRM -> confirmBooking(requestMessage);
                case LIST_RESERVATIONS -> listReservations(requestMessage.getClientId());
                case CANCEL -> cancelReservation(requestMessage);
                default -> System.out.println("Unknown client request type.");
            }
        }, consumerTag -> {});
    }

    private void listenForBuildingMessages() throws Exception {
        channel.basicConsume(AGENT_QUEUE_NAME, true, (consumerTag, delivery) -> {
            String jsonMessage = new String(delivery.getBody(), StandardCharsets.UTF_8);
            BuildingMessage buildingMessage;

            try {
                buildingMessage = objectMapper.readValue(jsonMessage, BuildingMessage.class);
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }

            System.out.println("Received building message of type: " + buildingMessage.getType());

            switch (buildingMessage.getType()) {
                case BUILDING_STATUS -> updateBuildingStatus(buildingMessage);
                case BOOK -> handleBookResponse(buildingMessage);
                case CANCEL -> handleCancelResponse(buildingMessage);
                case ERROR -> handleError(buildingMessage);
                default -> System.out.println("Unknown building message type.");
            }
        }, consumerTag -> {});
    }

    private void updateBuildingStatus(BuildingMessage buildingMessage) {
        availableRooms.put(buildingMessage.getBuildingName(), new HashSet<>(buildingMessage.getAvailableRooms()));
        buildingTimestamps.put(buildingMessage.getBuildingName(), System.currentTimeMillis());
        System.out.println("Updated available rooms for building: " + buildingMessage.getBuildingName());
    }

    private void handleBookResponse(BuildingMessage buildingMessage) {
        System.out.println("Building booking result: " + (buildingMessage.isSuccess() ? "Success" : "Failed"));
        updateBuildingStatus(buildingMessage);
    }

    private void handleCancelResponse(BuildingMessage buildingMessage) {
        System.out.println("Building cancellation result: " + (buildingMessage.isSuccess() ? "Success" : "Failed"));
        updateBuildingStatus(buildingMessage);
    }

    private void handleError(BuildingMessage buildingMessage) {
        System.out.println("Error received: " + buildingMessage.getErrorMessage());
    }

    private void sendBuildingList(String clientId) throws IOException {
        ClientMessage responseMessage = new ClientMessage(clientId, MessageType.LIST_BUILDINGS);

        Map<String, ArrayList<Integer>> bookingRooms = new HashMap<>();
        for (Map.Entry<String, Set<Integer>> entry : availableRooms.entrySet()) {
            bookingRooms.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        responseMessage.setBuildings(bookingRooms);
        sendResponse(clientId, responseMessage);
    }

    private void sendReservationNumber(ClientMessage requestMessage) throws IOException {
        String clientId = requestMessage.getClientId();
        Map<String, ArrayList<Integer>> requestedRooms = requestMessage.getBuildings();

        boolean allRoomsAvailable = true;
        synchronized (availableRooms) {
            allRoomsAvailable = isAllRoomsAvailable(requestedRooms, allRoomsAvailable);
        }

        if (!allRoomsAvailable) {
            ClientMessage errorMessage = new ClientMessage(clientId, MessageType.ERROR);
            errorMessage.setErrorMessage("Requested rooms are not available.");
            sendResponse(clientId, errorMessage);
            return;
        }

        String reservationNumber = UUID.randomUUID().toString();
        requestMessage.setReservationNumber(reservationNumber);
        unconfirmedReservations.put(reservationNumber, requestMessage);

        ClientMessage responseMessage = new ClientMessage(clientId, MessageType.BOOK);
        responseMessage.setReservationNumber(reservationNumber);
        responseMessage.setBuildings(requestMessage.getBuildings());
        sendResponse(clientId, responseMessage);
    }

    private boolean isAllRoomsAvailable(Map<String, ArrayList<Integer>> requestedRooms, boolean allRoomsAvailable) {
        for (Map.Entry<String, ArrayList<Integer>> entry : requestedRooms.entrySet()) {
            String building = entry.getKey();
            List<Integer> rooms = entry.getValue();

            Set<Integer> available = availableRooms.get(building);
            if (available == null || !available.containsAll(rooms)) {
                allRoomsAvailable = false;
                break;
            }
        }
        return allRoomsAvailable;
    }

    private void confirmBooking(ClientMessage requestMessage) throws IOException {
        String reservationNumber = requestMessage.getReservationNumber();
        String clientId = requestMessage.getClientId();

        ClientMessage unconfirmed = unconfirmedReservations.remove(reservationNumber);
        if (unconfirmed != null && unconfirmed.getClientId().equals(clientId)) {
            Map<String, ArrayList<Integer>> bookedRooms = unconfirmed.getBuildings();
            boolean allRoomsAvailable = true;

            synchronized (availableRooms) {
                allRoomsAvailable = isAllRoomsAvailable(bookedRooms, allRoomsAvailable);

                if (allRoomsAvailable) {
                    for (Map.Entry<String, ArrayList<Integer>> entry : bookedRooms.entrySet()) {
                        String building = entry.getKey();
                        Set<Integer> available = availableRooms.get(building);
                        if (available != null) {
                            available.removeAll(entry.getValue());
                        }
                    }

                    sendBookMessageToBuilding(unconfirmed);
                }
            }

            if (allRoomsAvailable) {
                reservations.computeIfAbsent(clientId, k -> Collections.synchronizedList(new ArrayList<>())).add(unconfirmed);
                ClientMessage responseMessage = new ClientMessage(clientId, MessageType.CONFIRM);
                responseMessage.setReservationNumber(reservationNumber);
                sendResponse(clientId, responseMessage);
            } else {
                ClientMessage errorMessage = new ClientMessage(clientId, MessageType.ERROR);
                errorMessage.setErrorMessage("Requested rooms are no longer available.");
                sendResponse(clientId, errorMessage);
            }
        } else {
            ClientMessage errorMessage = new ClientMessage(clientId, MessageType.ERROR);
            errorMessage.setErrorMessage("Invalid reservation number.");
            sendResponse(clientId, errorMessage);
        }
    }

    private void cancelReservation(ClientMessage requestMessage) throws IOException {
        String reservationNumber = requestMessage.getReservationNumber();
        String clientId = requestMessage.getClientId();
        List<ClientMessage> clientReservations = reservations.get(clientId);

        if (clientReservations != null) {
            ClientMessage toRemove = null;
            for (ClientMessage reservation : clientReservations) {
                if (reservation.getReservationNumber().equals(reservationNumber)) {
                    toRemove = reservation;
                    break;
                }
            }

            if (toRemove != null) {
                Map<String, ArrayList<Integer>> canceledRooms = toRemove.getBuildings();
                synchronized (availableRooms) {
                    for (Map.Entry<String, ArrayList<Integer>> entry : canceledRooms.entrySet()) {
                        availableRooms.computeIfAbsent(entry.getKey(), k -> ConcurrentHashMap.newKeySet()).addAll(entry.getValue());
                    }
                }

                clientReservations.remove(toRemove);

                sendCancelMessageToBuilding(toRemove);

                ClientMessage responseMessage = new ClientMessage(clientId, MessageType.CANCEL);
                responseMessage.setReservationNumber(reservationNumber);
                sendResponse(clientId, responseMessage);
                return;
            }
        }

        ClientMessage errorMessage = new ClientMessage(clientId, MessageType.ERROR);
        errorMessage.setErrorMessage("Reservation not found or already canceled.");
        sendResponse(clientId, errorMessage);
    }

    private void sendBookMessageToBuilding(ClientMessage clientMessage) throws IOException {
        BuildingMessage bookRequest = new BuildingMessage();
        bookRequest.setType(MessageType.BOOK);

        sendBookingRequest(clientMessage, bookRequest);

        System.out.println("Sent BOOK request to building: " + clientMessage.getBuildings().values().iterator().next());
    }

    private void sendBookingRequest(ClientMessage clientMessage, BuildingMessage bookRequest) throws IOException {
        String buildingName = clientMessage.getBuildings().keySet().iterator().next();
        bookRequest.setBuildingName(buildingName);
        bookRequest.setRequestedRooms(clientMessage.getBuildings().values().iterator().next());

        String message = objectMapper.writeValueAsString(bookRequest);

        channel.basicPublish(EXCHANGE_DIRECT, buildingName, null, message.getBytes(StandardCharsets.UTF_8));
    }

    private void sendCancelMessageToBuilding(ClientMessage clientMessage) throws IOException {
        BuildingMessage cancelRequest = new BuildingMessage();
        cancelRequest.setType(MessageType.CANCEL);

        sendBookingRequest(clientMessage, cancelRequest);

        System.out.println("Sent CANCEL request to building: " + clientMessage.getBuildings().values().iterator().next());
    }

    private void listReservations(String clientId) throws IOException {
        List<ClientMessage> clientReservations = reservations.get(clientId);
        ClientMessage responseMessage = new ClientMessage(clientId, MessageType.LIST_RESERVATIONS);

        if (clientReservations != null && !clientReservations.isEmpty()) {
            StringBuilder reservationNumbers = new StringBuilder();
            for (ClientMessage reservation : clientReservations) {
                if (!reservationNumbers.isEmpty()) {
                    reservationNumbers.append(",");
                }
                reservationNumbers.append(reservation.getReservationNumber());
            }
            responseMessage.setReservationNumber(reservationNumbers.toString());
        } else {
            responseMessage.setReservationNumber("");
        }

        sendResponse(clientId, responseMessage);
    }

    private void sendResponse(String clientId, ClientMessage responseMessage) throws IOException {
        String message = objectMapper.writeValueAsString(responseMessage);
        channel.basicPublish(EXCHANGE_CLIENT, clientId, null, message.getBytes(StandardCharsets.UTF_8));
    }
    private void removeInactiveBuildings(){
        new Thread(() -> {
            while (true) {
                try {
                    long currentTime = System.currentTimeMillis();

                    buildingTimestamps.forEach((buildingName, lastUpdated) -> {
                        if (currentTime - lastUpdated > TIMEOUT) {
                            availableRooms.remove(buildingName);
                            buildingTimestamps.remove(buildingName);
                            try {
                                requestBuildingStatus(buildingName);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            System.out.println("Removed inactive building: " + buildingName);
                        }
                    });

                    Thread.sleep(TIMEOUT);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void requestBuildingStatus(String buildingName) throws IOException {
        BuildingMessage request = new BuildingMessage();
        request.setType(MessageType.REQUEST_BUILDING_STATUS);
        request.setBuildingName(buildingName);

        String message = objectMapper.writeValueAsString(request);
        channel.basicPublish(EXCHANGE_DIRECT, buildingName, null, message.getBytes(StandardCharsets.UTF_8));

        System.out.println("Sent REQUEST_BUILDING_STATUS to building: " + buildingName);
    }

}