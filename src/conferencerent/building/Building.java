package conferencerent.building;

import com.rabbitmq.client.*;
import conferencerent.model.BuildingMessage;
import conferencerent.model.MessageType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Building {
    private static final String EXCHANGE_DIRECT = "direct_exchange";
    private static final String EXCHANGE_FANOUT = "building_announce_exchange";

    private final String buildingName;
    private final Set<Integer> availableRooms;
    private Channel channel;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        channel.exchangeDeclare(EXCHANGE_DIRECT, BuiltinExchangeType.DIRECT);
        channel.exchangeDeclare(EXCHANGE_FANOUT, BuiltinExchangeType.FANOUT);

        String buildingQueue = "building_queue_" + buildingName;
        channel.queueDeclare(buildingQueue, false, false, false, null);
        channel.queueBind(buildingQueue, EXCHANGE_DIRECT, buildingName);
        channel.queueBind(buildingQueue, EXCHANGE_FANOUT, buildingName);

        announceBuilding();
        listenForAgentRequests(buildingQueue);


    }

    private void announceBuilding() throws IOException {
        configureBuildingMessage();

        System.out.println("Building " + buildingName + " announced itself.");
    }

    private void configureBuildingMessage() throws IOException {
        BuildingMessage announcement = new BuildingMessage();
        announcement.setType(MessageType.BUILDING_STATUS);
        announcement.setBuildingName(buildingName);
        announcement.setAvailableRooms(new ArrayList<>(availableRooms));

        String message = objectMapper.writeValueAsString(announcement);
        channel.basicPublish(EXCHANGE_FANOUT, "", null, message.getBytes(StandardCharsets.UTF_8));
    }

    private void listenForAgentRequests(String queueName) throws IOException {
        channel.basicConsume(queueName, true, (consumerTag, delivery) -> {
            String jsonMessage = new String(delivery.getBody(), StandardCharsets.UTF_8);
            try {
                BuildingMessage message = objectMapper.readValue(jsonMessage, BuildingMessage.class);
                handleAgentRequest(message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, consumerTag -> {});
    }

    private void handleAgentRequest(BuildingMessage message) throws IOException {
        System.out.println("Processing request of type: " + message.getType());
        switch (message.getType()) {
            case REQUEST_BUILDING_STATUS -> sendStatusToAllAgents();
            case BOOK -> processBookingRequest(message);
            case CANCEL -> processCancellationRequest(message);
            case ERROR -> System.out.println("Error received: " + message.getErrorMessage());
        }
    }

    private void sendStatusToAllAgents() throws IOException {
        configureBuildingMessage();

        System.out.println("Building " + buildingName + " sent status to all agents.");
    }

    private void processBookingRequest(BuildingMessage request) throws IOException {
        List<Integer> requestedRooms = request.getRequestedRooms();
        boolean success = availableRooms.containsAll(requestedRooms);

        if (success) {
            requestedRooms.forEach(availableRooms::remove);
        }

        System.out.println("Updated room availability after booking: " + availableRooms);

        sendStatusToAllAgents();
    }

    private void processCancellationRequest(BuildingMessage request) throws IOException {
        availableRooms.addAll(request.getRequestedRooms());
        System.out.println("Updated room availability after cancellation: " + availableRooms);
        sendStatusToAllAgents();
    }
}
