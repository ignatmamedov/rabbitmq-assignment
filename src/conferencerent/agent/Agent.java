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

/**
 * The Agent class acts as a mediator between clients and buildings.
 * It handles client requests for booking conference rooms,
 * communicates with buildings to update room availability,
 * and manages reservations.
 */
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

    /**
     * The main method to run the Agent.
     *
     * @param args command-line arguments
     * @throws Exception if an error occurs during execution
     */
    public static void main(String[] args) throws Exception {
        new Agent().run();
    }

    /**
     * Runs the agent by initializing connections, setting up listeners,
     * and handling client and building messages.
     *
     * @throws Exception if an error occurs during execution
     */
    public void run() throws Exception {
        initialize();
        listenForReplicationMessages();
        requestBuildingStatusFromAll();
        listenForBuildingMessages();
        removeInactiveBuildings();
        waitForAvailableRooms();
        listenForClientRequests();
    }

    /**
     * Initializes the RabbitMQ connections, exchanges, and queues.
     *
     * @throws Exception if an error occurs during initialization
     */
    private void initialize() throws Exception {
        objectMapper.configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, true);
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        Connection connection = factory.newConnection();
        channel = connection.createChannel();
        declareExchangesAndQueues();
    }

    /**
     * Declares the necessary exchanges and queues for communication.
     *
     * @throws IOException if an error occurs during declaration
     */
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

    /**
     * Requests the status of all buildings by broadcasting a message.
     *
     * @throws IOException if an error occurs during publishing
     */
    private void requestBuildingStatusFromAll() throws IOException {
        BuildingMessage request = new BuildingMessage();
        request.setType(MessageType.REQUEST_BUILDING_STATUS);
        String message = objectMapper.writeValueAsString(request);
        channel.basicPublish(
                EXCHANGE_FANOUT, "", null, message.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Starts listening for client requests.
     *
     * @throws IOException if an error occurs during consuming messages
     */
    private void listenForClientRequests() throws IOException {
        channel.basicConsume(CLIENT_AGENT_QUEUE, true, this::handleClientRequest, consumerTag -> {});
    }

    /**
     * Handles incoming client requests and dispatches them to appropriate methods.
     *
     * @param consumerTag the consumer tag
     * @param delivery    the message delivery
     */
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

    /**
     * Starts listening for building messages.
     *
     * @throws IOException if an error occurs during consuming messages
     */
    private void listenForBuildingMessages() throws IOException {
        channel.basicConsume(AGENT_QUEUE_NAME, true, this::handleBuildingMessage, consumerTag -> {});
    }

    /**
     * Handles incoming messages from buildings and updates room availability.
     *
     * @param consumerTag the consumer tag
     * @param delivery    the message delivery
     */
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

    /**
     * Waits until rooms are available before proceeding.
     */
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

    /**
     * Updates the available rooms and timestamps for a building.
     *
     * @param buildingMessage the building message containing the status
     */
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

    /**
     * Sends the list of available buildings and rooms to the client.
     *
     * @param clientId the client's unique identifier
     * @throws IOException if an error occurs during publishing
     */
    private void sendBuildingList(String clientId) throws IOException {
        ClientMessage responseMessage = new ClientMessage(clientId, MessageType.LIST_BUILDINGS);
        Map<String, ArrayList<Integer>> bookingRooms = new HashMap<>();
        availableRooms.forEach((k, v) -> bookingRooms.put(k, new ArrayList<>(v)));
        responseMessage.setBuildings(bookingRooms);
        sendResponse(clientId, responseMessage);
    }

    /**
     * Processes a booking request from a client.
     *
     * @param requestMessage the client's booking request
     * @throws IOException if an error occurs during processing
     */
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

    /**
     * Checks if the requested rooms are available.
     *
     * @param requestedRooms the rooms requested by the client
     * @return true if all requested rooms are available; false otherwise
     */
    private boolean areRoomsAvailable(Map<String, ArrayList<Integer>> requestedRooms) {
        for (Map.Entry<String, ArrayList<Integer>> entry : requestedRooms.entrySet()) {
            Set<Integer> available = availableRooms.get(entry.getKey());
            if (available == null || !available.containsAll(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Confirms a booking for a client.
     *
     * @param requestMessage the client's confirmation request
     * @throws IOException if an error occurs during processing
     */
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

    /**
     * Updates the available rooms by removing the booked rooms.
     *
     * @param rooms the rooms that have been booked
     */
    private void updateAvailableRooms(Map<String, ArrayList<Integer>> rooms) {
        rooms.forEach((building, roomList) -> {
            Set<Integer> available = availableRooms.get(building);
            if (available != null) {
                roomList.forEach(available::remove);
            }
        });
    }

    /**
     * Cancels a reservation for a client.
     *
     * @param requestMessage the client's cancellation request
     * @throws IOException if an error occurs during processing
     */
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

    /**
     * Updates the available rooms by adding back the canceled rooms.
     *
     * @param rooms the rooms that have been canceled
     */
    private void updateAvailableRoomsAfterCancellation(Map<String, ArrayList<Integer>> rooms) {
        rooms.forEach(
                (building, roomList) -> availableRooms.computeIfAbsent(
                        building, k -> ConcurrentHashMap.newKeySet()).addAll(roomList)
        );
    }

    /**
     * Sends a booking or cancellation request to a building.
     *
     * @param clientMessage the client's booking or cancellation message
     * @param type          the type of message (BOOK or CANCEL)
     * @throws IOException if an error occurs during publishing
     */
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

    /**
     * Lists all reservations for a client.
     *
     * @param clientId the client's unique identifier
     * @throws IOException if an error occurs during publishing
     */
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

    /**
     * Sends a response message to a client.
     *
     * @param clientId       the client's unique identifier
     * @param responseMessage the response message to send
     * @throws IOException if an error occurs during publishing
     */
    private void sendResponse(String clientId, ClientMessage responseMessage) throws IOException {
        String message = objectMapper.writeValueAsString(responseMessage);
        channel.basicPublish(
                EXCHANGE_CLIENT, clientId, null, message.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Sends an error message to a client.
     *
     * @param clientId     the client's unique identifier
     * @param errorMessage the error message to send
     * @throws IOException if an error occurs during publishing
     */
    private void sendError(String clientId, String errorMessage) throws IOException {
        ClientMessage errorMessageObj = new ClientMessage(clientId, MessageType.ERROR);
        errorMessageObj.setErrorMessage(errorMessage);
        sendResponse(clientId, errorMessageObj);
    }

    /**
     * Periodically removes buildings that have not sent status updates within the timeout period.
     */
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

    /**
     * Requests the status of a specific building.
     *
     * @param buildingName the name of the building
     * @throws IOException if an error occurs during publishing
     */
    private void requestBuildingStatus(String buildingName) throws IOException {
        BuildingMessage request = new BuildingMessage();
        request.setType(MessageType.REQUEST_BUILDING_STATUS);
        request.setBuildingName(buildingName);
        String message = objectMapper.writeValueAsString(request);
        channel.basicPublish(
                EXCHANGE_DIRECT, buildingName, null, message.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Replicates a confirmed booking to other agents.
     *
     * @param bookingMessage the booking message to replicate
     * @throws IOException if an error occurs during publishing
     */
    private void replicateBooking(ClientMessage bookingMessage) throws IOException {
        String message = objectMapper.writeValueAsString(bookingMessage);
        channel.basicPublish(
                EXCHANGE_REPLICATION, "", null, message.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Replicates an unconfirmed booking to other agents.
     *
     * @param bookingMessage the booking message to replicate
     * @throws IOException if an error occurs during publishing
     */
    private void replicateUnconfirmedBooking(ClientMessage bookingMessage) throws IOException {
        bookingMessage.setType(MessageType.BOOK);
        String message = objectMapper.writeValueAsString(bookingMessage);
        channel.basicPublish(
                EXCHANGE_REPLICATION, "", null, message.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Starts listening for replication messages from other agents.
     *
     * @throws IOException if an error occurs during consuming messages
     */
    private void listenForReplicationMessages() throws IOException {
        String replicationQueue = "replication_queue_" + UUID.randomUUID();
        channel.queueDeclare(replicationQueue, false, false, true, null);
        channel.queueBind(replicationQueue, EXCHANGE_REPLICATION, "");
        channel.basicConsume(replicationQueue, true, this::handleReplicationMessage, consumerTag -> {});
    }

    /**
     * Handles incoming replication messages to synchronize bookings across agents.
     *
     * @param consumerTag the consumer tag
     * @param delivery    the message delivery
     */
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
