package conferencerent.building;

import com.rabbitmq.client.*;
import conferencerent.model.BuildingMessage;
import conferencerent.model.MessageType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeoutException;

public class Building {
    private static final String EXCHANGE_DIRECT = "direct_exchange";
    private static final String EXCHANGE_FANOUT = "building_announce_exchange";

    private final String buildingName;
    private final Set<Integer> availableRooms;
    private Channel channel;
    private ObjectMapper objectMapper = new ObjectMapper();

    public Building(String buildingName, Set<Integer> rooms) {
        this.buildingName = buildingName;
        this.availableRooms = Collections.synchronizedSet(new HashSet<>(rooms));
    }

    public static void main(String[] args) throws Exception {
        new Building("Building A", new HashSet<>(Arrays.asList(101, 102, 103))).run();
        new Building("Building B", new HashSet<>(Arrays.asList(201, 202, 203))).run();
        new Building("Building C", new HashSet<>(Arrays.asList(301, 302, 303))).run();
    }

    public void run() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        Connection connection = factory.newConnection();
        channel = connection.createChannel();

        // Declare exchanges for communication
        channel.exchangeDeclare(EXCHANGE_DIRECT, BuiltinExchangeType.DIRECT);
        channel.exchangeDeclare(EXCHANGE_FANOUT, BuiltinExchangeType.FANOUT);

        // Declare queue for the building to receive messages
        String buildingQueue = "building_queue_" + buildingName;
        channel.queueDeclare(buildingQueue, false, false, false, null);
        channel.queueBind(buildingQueue, EXCHANGE_DIRECT, buildingName);
        channel.queueBind(buildingQueue, EXCHANGE_FANOUT, buildingName);

        announceBuilding();  // Broadcast building's existence
        listenForAgentRequests(buildingQueue);  // Listen for requests from agents
    }

    // Announce the building's presence by sending an initial status update
    private void announceBuilding() throws IOException {
        BuildingMessage announcement = new BuildingMessage();
        announcement.setType(MessageType.BUILDING_STATUS);
        announcement.setBuildingName(buildingName);
        announcement.setAvailableRooms(new ArrayList<>(availableRooms));

        String message = objectMapper.writeValueAsString(announcement);
        channel.basicPublish(EXCHANGE_FANOUT, "", null, message.getBytes(StandardCharsets.UTF_8));

        System.out.println("Building " + buildingName + " announced itself.");
    }

    // Listen for requests from agents (BOOK, CANCEL, etc.)
    private void listenForAgentRequests(String queueName) throws IOException {
        channel.basicConsume(queueName, true, (consumerTag, delivery) -> {
            String jsonMessage = new String(delivery.getBody(), StandardCharsets.UTF_8);
            try {
                BuildingMessage message = objectMapper.readValue(jsonMessage, BuildingMessage.class);
                handleAgentRequest(message);  // Process the message from the agent
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, consumerTag -> {});
    }

    private void handleAgentRequest(BuildingMessage message) throws IOException {
        System.out.println("Processing request of type: " + message.getType());
        switch (message.getType()) {
            case REQUEST_BUILDING_STATUS -> sendStatusToAllAgents();  // Отправляем статус всем агентам
            case BOOK -> processBookingRequest(message);  // Process booking request
            case CANCEL -> processCancellationRequest(message);  // Process cancellation request
            case ERROR -> System.out.println("Error received: " + message.getErrorMessage());  // Handle errors
        }
    }

    // Send the current room availability status to all agents
    private void sendStatusToAllAgents() throws IOException {
        BuildingMessage statusUpdate = new BuildingMessage();
        statusUpdate.setType(MessageType.BUILDING_STATUS);
        statusUpdate.setBuildingName(buildingName);
        statusUpdate.setAvailableRooms(new ArrayList<>(availableRooms));

        String message = objectMapper.writeValueAsString(statusUpdate);
        channel.basicPublish(EXCHANGE_FANOUT, "", null, message.getBytes(StandardCharsets.UTF_8));

        System.out.println("Building " + buildingName + " sent status to all agents.");
    }

    // Process a booking request from an agent
    private void processBookingRequest(BuildingMessage request) throws IOException {
        List<Integer> requestedRooms = request.getRequestedRooms();
        boolean success = availableRooms.containsAll(requestedRooms);  // Check if all requested rooms are available

        if (success) {
            availableRooms.removeAll(requestedRooms);  // Reserve the rooms by removing them from availableRooms
        }

        System.out.println("Updated room availability after booking: " + availableRooms);

        sendStatusToAllAgents();  // Notify all agents about updated availability
    }

    // Process a cancellation request from an agent
    private void processCancellationRequest(BuildingMessage request) throws IOException {
        availableRooms.addAll(request.getRequestedRooms());  // Add rooms back to availability
        System.out.println("Updated room availability after cancellation: " + availableRooms);
        sendStatusToAllAgents();  // Notify all agents about updated availability
    }
}
