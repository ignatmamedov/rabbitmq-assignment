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
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Agent {
    private static final int TIMEOUT = 1000;
    private static final String EXCHANGE_CLIENT = "client_exchange";
    private static final String EXCHANGE_DIRECT = "direct_exchange";
    private static final String EXCHANGE_FANOUT = "building_announce_exchange";
    private static final String EXCHANGE_REPLICATION = "replication_exchange";
    private static final String CLIENT_AGENT_QUEUE = "client_to_agent_queue";
    private static final String AGENT_QUEUE_NAME = "agent_to_building_queue_" + UUID.randomUUID();

    private final Map<String, List<ClientMessage>> reservations = new ConcurrentHashMap<>();
    private final Map<String, ClientMessage> unconfirmedReservations = new ConcurrentHashMap<>();
    private final Map<String, Set<Integer>> availableRooms = new ConcurrentHashMap<>();
    private final Map<String, Long> buildingTimestamps = new ConcurrentHashMap<>();

    private final Lock lock = new ReentrantLock();
    private final Condition roomsAvailable = lock.newCondition();
    private Channel channel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        new Agent().run();
    }

    public void run() throws Exception {
        initialize();
        listenForReplicationMessages();
        requestBuildingStatusFromAll();
        listenForBuildingMessages();
        removeInactiveBuildings();
        waitForAvailableRooms();
        listenForClientRequests();
    }

    private void initialize() throws Exception {
        objectMapper.configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, true);
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        Connection connection = factory.newConnection();
        channel = connection.createChannel();
        declareExchangesAndQueues();
    }

    private void declareExchangesAndQueues() throws IOException {
        channel.exchangeDeclare(EXCHANGE_CLIENT, BuiltinExchangeType.DIRECT);
        channel.exchangeDeclare(EXCHANGE_FANOUT, BuiltinExchangeType.FANOUT);
        channel.exchangeDeclare(EXCHANGE_REPLICATION, BuiltinExchangeType.FANOUT);

        channel.queueDeclare(CLIENT_AGENT_QUEUE, false, false, false, null);
        channel.queueBind(CLIENT_AGENT_QUEUE, EXCHANGE_CLIENT, "client_to_agent");

        channel.queueDeclare(AGENT_QUEUE_NAME, false, false, false, null);
        channel.queueBind(AGENT_QUEUE_NAME, EXCHANGE_DIRECT, "agent_building_interaction");
        channel.queueBind(AGENT_QUEUE_NAME, EXCHANGE_FANOUT, "");
    }

    private void requestBuildingStatusFromAll() throws IOException {
        BuildingMessage request = new BuildingMessage();
        request.setType(MessageType.REQUEST_BUILDING_STATUS);
        String message = objectMapper.writeValueAsString(request);
        channel.basicPublish(
                EXCHANGE_FANOUT, "", null, message.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void listenForClientRequests() throws IOException {
        channel.basicConsume(CLIENT_AGENT_QUEUE, true, this::handleClientRequest, consumerTag -> {});
    }

    private void handleClientRequest(String consumerTag, Delivery delivery) {
        String jsonRequest = new String(delivery.getBody(), StandardCharsets.UTF_8);
        try {
            ClientMessage requestMessage = objectMapper.readValue(jsonRequest, ClientMessage.class);
            switch (requestMessage.getType()) {
                case LIST_BUILDINGS -> sendBuildingList(requestMessage.getClientId());
                case BOOK -> processBookingRequest(requestMessage);
                case CONFIRM -> confirmBooking(requestMessage);
                case LIST_RESERVATIONS -> listReservations(requestMessage.getClientId());
                case CANCEL -> cancelReservation(requestMessage);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void listenForBuildingMessages() throws IOException {
        channel.basicConsume(AGENT_QUEUE_NAME, true, this::handleBuildingMessage, consumerTag -> {});
    }

    private void handleBuildingMessage(String consumerTag, Delivery delivery) {
        String jsonMessage = new String(delivery.getBody(), StandardCharsets.UTF_8);
        try {
            BuildingMessage buildingMessage = objectMapper.readValue(jsonMessage, BuildingMessage.class);
            if (buildingMessage.getType() == MessageType.BUILDING_STATUS) {
                updateBuildingStatus(buildingMessage);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void waitForAvailableRooms() {
        lock.lock();
        try {
            while (availableRooms.isEmpty()) {
                roomsAvailable.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    private void updateBuildingStatus(BuildingMessage buildingMessage) {
        lock.lock();
        try {
            availableRooms.put(
                    buildingMessage.getBuildingName(), new HashSet<>(buildingMessage.getAvailableRooms())
            );
            buildingTimestamps.put(buildingMessage.getBuildingName(), System.currentTimeMillis());
            roomsAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void sendBuildingList(String clientId) throws IOException {
        ClientMessage responseMessage = new ClientMessage(clientId, MessageType.LIST_BUILDINGS);
        Map<String, ArrayList<Integer>> bookingRooms = new HashMap<>();
        availableRooms.forEach((k, v) -> bookingRooms.put(k, new ArrayList<>(v)));
        responseMessage.setBuildings(bookingRooms);
        sendResponse(clientId, responseMessage);
    }

    private void processBookingRequest(ClientMessage requestMessage) throws IOException {
        String clientId = requestMessage.getClientId();
        Map<String, ArrayList<Integer>> requestedRooms = requestMessage.getBuildings();
        if (!areRoomsAvailable(requestedRooms)) {
            sendError(clientId, "Requested rooms are not available.");
            return;
        }
        String reservationNumber = UUID.randomUUID().toString();
        requestMessage.setReservationNumber(reservationNumber);
        unconfirmedReservations.put(reservationNumber, requestMessage);
        ClientMessage responseMessage = new ClientMessage(clientId, MessageType.BOOK);
        responseMessage.setReservationNumber(reservationNumber);
        responseMessage.setBuildings(requestMessage.getBuildings());
        sendResponse(clientId, responseMessage);
        replicateUnconfirmedBooking(requestMessage);
    }

    private boolean areRoomsAvailable(Map<String, ArrayList<Integer>> requestedRooms) {
        for (Map.Entry<String, ArrayList<Integer>> entry : requestedRooms.entrySet()) {
            Set<Integer> available = availableRooms.get(entry.getKey());
            if (available == null || !available.containsAll(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private void confirmBooking(ClientMessage requestMessage) throws IOException {
        String reservationNumber = requestMessage.getReservationNumber();
        String clientId = requestMessage.getClientId();
        ClientMessage unconfirmed = unconfirmedReservations.remove(reservationNumber);
        if (unconfirmed != null && unconfirmed.getClientId().equals(clientId)) {
            Map<String, ArrayList<Integer>> bookedRooms = unconfirmed.getBuildings();
            if (!areRoomsAvailable(bookedRooms)) {
                sendError(clientId, "Requested rooms are no longer available.");
                return;
            }
            updateAvailableRooms(bookedRooms);
            sendBookingRequestToBuilding(unconfirmed, MessageType.BOOK);
            reservations.computeIfAbsent(
                    clientId, k -> Collections.synchronizedList(new ArrayList<>())
            ).add(unconfirmed);
            ClientMessage responseMessage = new ClientMessage(clientId, MessageType.CONFIRM);
            responseMessage.setReservationNumber(reservationNumber);
            sendResponse(clientId, responseMessage);
            unconfirmed.setType(MessageType.CONFIRM);
            replicateBooking(unconfirmed);
        } else {
            sendError(clientId, "Invalid reservation number.");
        }
    }

    private void updateAvailableRooms(Map<String, ArrayList<Integer>> rooms) {
        rooms.forEach((building, roomList) -> {
            Set<Integer> available = availableRooms.get(building);
            if (available != null) {
                roomList.forEach(available::remove);
            }
        });
    }

    private void cancelReservation(ClientMessage requestMessage) throws IOException {
        String reservationNumber = requestMessage.getReservationNumber();
        String clientId = requestMessage.getClientId();
        List<ClientMessage> clientReservations = reservations.get(clientId);
        if (clientReservations != null) {
            ClientMessage toRemove = clientReservations.stream()
                    .filter(reservation -> reservation.getReservationNumber().equals(reservationNumber))
                    .findFirst().orElse(null);
            if (toRemove != null) {
                updateAvailableRoomsAfterCancellation(toRemove.getBuildings());
                clientReservations.remove(toRemove);
                sendBookingRequestToBuilding(toRemove, MessageType.CANCEL);
                requestMessage.setType(MessageType.CANCEL);
                replicateBooking(requestMessage);
                ClientMessage responseMessage = new ClientMessage(clientId, MessageType.CANCEL);
                responseMessage.setReservationNumber(reservationNumber);
                sendResponse(clientId, responseMessage);
                return;
            }
        }
        sendError(clientId, "Reservation not found or already canceled.");
    }

    private void updateAvailableRoomsAfterCancellation(Map<String, ArrayList<Integer>> rooms) {
        rooms.forEach(
                (building, roomList) -> availableRooms.computeIfAbsent(
                        building, k -> ConcurrentHashMap.newKeySet()).addAll(roomList)
        );
    }

    private void sendBookingRequestToBuilding(ClientMessage clientMessage, MessageType type) throws IOException {
        BuildingMessage request = new BuildingMessage();
        request.setType(type);
        String buildingName = clientMessage.getBuildings().keySet().iterator().next();
        request.setBuildingName(buildingName);
        request.setRequestedRooms(clientMessage.getBuildings().values().iterator().next());
        String message = objectMapper.writeValueAsString(request);
        channel.basicPublish(
                EXCHANGE_DIRECT, buildingName, null, message.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void listReservations(String clientId) throws IOException {
        List<ClientMessage> clientReservations = reservations.get(clientId);
        ClientMessage responseMessage = new ClientMessage(clientId, MessageType.LIST_RESERVATIONS);
        if (clientReservations != null && !clientReservations.isEmpty()) {
            String reservationNumbers = String.join(",", clientReservations.stream()
                    .map(ClientMessage::getReservationNumber).toArray(String[]::new));
            responseMessage.setReservationNumber(reservationNumbers);
        } else {
            responseMessage.setReservationNumber("");
        }
        sendResponse(clientId, responseMessage);
    }

    private void sendResponse(String clientId, ClientMessage responseMessage) throws IOException {
        String message = objectMapper.writeValueAsString(responseMessage);
        channel.basicPublish(
                EXCHANGE_CLIENT, clientId, null, message.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void sendError(String clientId, String errorMessage) throws IOException {
        ClientMessage errorMessageObj = new ClientMessage(clientId, MessageType.ERROR);
        errorMessageObj.setErrorMessage(errorMessage);
        sendResponse(clientId, errorMessageObj);
    }

    private void removeInactiveBuildings() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            long currentTime = System.currentTimeMillis();
            buildingTimestamps.forEach((buildingName, lastUpdated) -> {
                if (currentTime - lastUpdated > TIMEOUT) {
                    availableRooms.remove(buildingName);
                    buildingTimestamps.remove(buildingName);
                    try {
                        requestBuildingStatus(buildingName);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        }, TIMEOUT, TIMEOUT, TimeUnit.MILLISECONDS);
    }

    private void requestBuildingStatus(String buildingName) throws IOException {
        BuildingMessage request = new BuildingMessage();
        request.setType(MessageType.REQUEST_BUILDING_STATUS);
        request.setBuildingName(buildingName);
        String message = objectMapper.writeValueAsString(request);
        channel.basicPublish(
                EXCHANGE_DIRECT, buildingName, null, message.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void replicateBooking(ClientMessage bookingMessage) throws IOException {
        String message = objectMapper.writeValueAsString(bookingMessage);
        channel.basicPublish(
                EXCHANGE_REPLICATION, "", null, message.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void replicateUnconfirmedBooking(ClientMessage bookingMessage) throws IOException {
        bookingMessage.setType(MessageType.BOOK);
        String message = objectMapper.writeValueAsString(bookingMessage);
        channel.basicPublish(
                EXCHANGE_REPLICATION, "", null, message.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void listenForReplicationMessages() throws IOException {
        String replicationQueue = "replication_queue_" + UUID.randomUUID();
        channel.queueDeclare(replicationQueue, false, false, true, null);
        channel.queueBind(replicationQueue, EXCHANGE_REPLICATION, "");
        channel.basicConsume(replicationQueue, true, this::handleReplicationMessage, consumerTag -> {});
    }

    private void handleReplicationMessage(String consumerTag, Delivery delivery) {
        String jsonMessage = new String(delivery.getBody(), StandardCharsets.UTF_8);
        try {
            ClientMessage replicationMessage = objectMapper.readValue(jsonMessage, ClientMessage.class);
            switch (replicationMessage.getType()) {
                case BOOK -> unconfirmedReservations.put(
                        replicationMessage.getReservationNumber(), replicationMessage
                );
                case CONFIRM -> {
                    List<ClientMessage> clientReservations = reservations
                            .computeIfAbsent(
                                    replicationMessage.getClientId(), k -> Collections.synchronizedList(
                                            new ArrayList<>())
                            );
                    if (
                            clientReservations.stream()
                                    .noneMatch(
                                            existingReservation -> existingReservation
                                                    .getReservationNumber()
                                                    .equals(
                                                            replicationMessage.getReservationNumber()
                                                    )
                                    )
                    ) {
                        clientReservations.add(replicationMessage);
                    }
                }
                case CANCEL -> {
                    List<ClientMessage> clientReservations = reservations.get(replicationMessage.getClientId());
                    if (clientReservations != null) {
                        clientReservations.removeIf(
                                reservation -> reservation.getReservationNumber()
                                        .equals(replicationMessage.getReservationNumber()));
                    }
                    unconfirmedReservations.remove(replicationMessage.getReservationNumber());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
