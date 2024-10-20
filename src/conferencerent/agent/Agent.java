package conferencerent.agent;

import com.rabbitmq.client.*;
import conferencerent.model.ClientRequestMessage;
import conferencerent.model.ClientRequestType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Agent {
    private static final String EXCHANGE_CLIENT = "client_exchange";
    private static final String CLIENT_AGENT_QUEUE = "client_to_agent_queue";

    // Store confirmed reservations per client
    private static final Map<String, List<ClientRequestMessage>> reservations = new ConcurrentHashMap<>();
    // Temporarily store unconfirmed reservations
    private static final Map<String, ClientRequestMessage> unconfirmedReservations = new ConcurrentHashMap<>();

    // Store available rooms per building
    private static final Map<String, Set<Integer>> availableRooms = new ConcurrentHashMap<>();

    private static Channel channel;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        new Agent().run();
    }

    public void run() throws Exception {
        objectMapper.configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, true);
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        // Set your RabbitMQ username and password if different
        factory.setUsername("guest");
        factory.setPassword("guest");
        factory.setPort(5672);
        Connection connection = factory.newConnection();
        channel = connection.createChannel();

        channel.exchangeDeclare(EXCHANGE_CLIENT, BuiltinExchangeType.DIRECT);

        // Declare the queue for receiving messages from clients
        channel.queueDeclare(CLIENT_AGENT_QUEUE, false, false, false, null);
        channel.queueBind(CLIENT_AGENT_QUEUE, EXCHANGE_CLIENT, "client_to_agent");

        initializeAvailableRooms();

        listenForClientRequests();
    }

    // Initialize available rooms
    private void initializeAvailableRooms() {
        Map<String, Set<Integer>> initialRooms = new HashMap<>();
        initialRooms.put("Building A", ConcurrentHashMap.newKeySet());
        initialRooms.get("Building A").addAll(Arrays.asList(101, 102));
        initialRooms.put("Building B", ConcurrentHashMap.newKeySet());
        initialRooms.get("Building B").addAll(Arrays.asList(201, 202));
        availableRooms.putAll(initialRooms);
    }

    private void listenForClientRequests() throws Exception {
        channel.basicConsume(CLIENT_AGENT_QUEUE, true, (consumerTag, delivery) -> {
            String jsonRequest = new String(delivery.getBody(), StandardCharsets.UTF_8);
            ClientRequestMessage requestMessage;

            try {
                requestMessage = objectMapper.readValue(jsonRequest, ClientRequestMessage.class);
            } catch (Exception e) {
                e.printStackTrace();
                return;
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

        // Create a deep copy of the availableRooms to avoid concurrent modification
        Map<String, ArrayList<Integer>> bookingRooms = new HashMap<>();
        for (Map.Entry<String, Set<Integer>> entry : availableRooms.entrySet()) {
            synchronized (entry.getValue()) {
                bookingRooms.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }

        responseMessage.setBuildings(bookingRooms);
        sendResponse(clientId, responseMessage);
    }

    private void sendReservationNumber(ClientRequestMessage requestMessage) throws IOException {
        String clientId = requestMessage.getClientId();
        Map<String, ArrayList<Integer>> requestedRooms = requestMessage.getBuildings();

        // Validate requested rooms
        boolean allRoomsAvailable = true;
        synchronized (availableRooms) {
            for (Map.Entry<String, ArrayList<Integer>> entry : requestedRooms.entrySet()) {
                String building = entry.getKey();
                List<Integer> rooms = entry.getValue();

                Set<Integer> available = availableRooms.get(building);
                if (available == null || !available.containsAll(rooms)) {
                    allRoomsAvailable = false;
                    break;
                }
            }
        }

        if (!allRoomsAvailable) {
            // Send error message to client
            ClientRequestMessage errorMessage = new ClientRequestMessage(clientId, ClientRequestType.ERROR);
            errorMessage.setErrorMessage("Requested rooms are not available.");
            sendResponse(clientId, errorMessage);
            return;
        }

        // Generate a reservation number
        String reservationNumber = UUID.randomUUID().toString();

        // Store the unconfirmed reservation
        requestMessage.setReservationNumber(reservationNumber);
        unconfirmedReservations.put(reservationNumber, requestMessage);

        // Send response back to client
        ClientRequestMessage responseMessage = new ClientRequestMessage(clientId, ClientRequestType.BOOK);
        responseMessage.setReservationNumber(reservationNumber);
        responseMessage.setBuildings(requestMessage.getBuildings());
        sendResponse(clientId, responseMessage);
    }

    private void confirmBooking(ClientRequestMessage requestMessage) throws IOException {
        String reservationNumber = requestMessage.getReservationNumber();
        String clientId = requestMessage.getClientId();

        // Retrieve the unconfirmed reservation
        ClientRequestMessage unconfirmed = unconfirmedReservations.remove(reservationNumber);

        if (unconfirmed != null && unconfirmed.getClientId().equals(clientId)) {
            Map<String, ArrayList<Integer>> bookedRooms = unconfirmed.getBuildings();

            // Validate and remove rooms from availableRooms
            boolean allRoomsAvailable = true;
            synchronized (availableRooms) {
                for (Map.Entry<String, ArrayList<Integer>> entry : bookedRooms.entrySet()) {
                    String building = entry.getKey();
                    List<Integer> rooms = entry.getValue();

                    Set<Integer> available = availableRooms.get(building);
                    if (available == null || !available.containsAll(rooms)) {
                        allRoomsAvailable = false;
                        break;
                    }
                }

                if (allRoomsAvailable) {
                    // Remove rooms from availableRooms
                    for (Map.Entry<String, ArrayList<Integer>> entry : bookedRooms.entrySet()) {
                        String building = entry.getKey();
                        List<Integer> rooms = entry.getValue();

                        Set<Integer> available = availableRooms.get(building);
                        if (available != null) {
                            available.removeAll(rooms);
                        }
                    }
                }
            }

            if (allRoomsAvailable) {
                // Add to confirmed reservations
                reservations.computeIfAbsent(clientId, k -> Collections.synchronizedList(new ArrayList<>())).add(unconfirmed);

                // Send confirmation to client
                ClientRequestMessage responseMessage = new ClientRequestMessage(clientId, ClientRequestType.CONFIRM);
                responseMessage.setReservationNumber(reservationNumber);
                sendResponse(clientId, responseMessage);
            } else {
                // Send error message to client
                ClientRequestMessage errorMessage = new ClientRequestMessage(clientId, ClientRequestType.ERROR);
                errorMessage.setErrorMessage("Requested rooms are no longer available.");
                sendResponse(clientId, errorMessage);
            }
        } else {
            // Send error message to client
            ClientRequestMessage errorMessage = new ClientRequestMessage(clientId, ClientRequestType.ERROR);
            errorMessage.setErrorMessage("Invalid reservation number.");
            sendResponse(clientId, errorMessage);
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

        sendResponse(clientId, responseMessage);
    }

    private void cancelReservation(ClientRequestMessage requestMessage) throws IOException {
        String reservationNumber = requestMessage.getReservationNumber();
        String clientId = requestMessage.getClientId();

        // Check in confirmed reservations
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
                Map<String, ArrayList<Integer>> canceledRooms = toRemove.getBuildings();

                // Return rooms to availableRooms
                synchronized (availableRooms) {
                    for (Map.Entry<String, ArrayList<Integer>> entry : canceledRooms.entrySet()) {
                        String building = entry.getKey();
                        List<Integer> rooms = entry.getValue();

                        availableRooms.computeIfAbsent(building, k -> ConcurrentHashMap.newKeySet()).addAll(rooms);
                    }
                }

                clientReservations.remove(toRemove);

                // Send cancellation confirmation
                ClientRequestMessage responseMessage = new ClientRequestMessage(clientId, ClientRequestType.CANCEL);
                responseMessage.setReservationNumber(reservationNumber);
                sendResponse(clientId, responseMessage);
                return;
            }
        }

        // Send error message
        ClientRequestMessage errorMessage = new ClientRequestMessage(clientId, ClientRequestType.ERROR);
        errorMessage.setErrorMessage("Reservation not found or already canceled.");
        sendResponse(clientId, errorMessage);
    }

    // Method to send responses to clients
    private void sendResponse(String clientId, ClientRequestMessage responseMessage) throws IOException {
        String message = objectMapper.writeValueAsString(responseMessage);
        channel.basicPublish(EXCHANGE_CLIENT, clientId, null, message.getBytes(StandardCharsets.UTF_8));
    }
}
