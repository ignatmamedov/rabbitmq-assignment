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

    public static void main(String[] args){
        List<Building> buildings = Arrays.asList(
                new Building("Building A", Set.of(101, 102, 103)),
                new Building("Building B", Set.of(201, 202, 203)),
                new Building("Building C", Set.of(301, 302, 303))
        );
        for (Building building : buildings) {
            new Thread(() -> {
                try {
                    building.run();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    public void run() throws Exception {
        setupConnection();
        announceBuilding();
        listenForAgentRequests();
    }

    private void setupConnection() throws Exception {
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
    }

    private void announceBuilding() throws IOException {
        sendStatusToAllAgents();
    }

    private void listenForAgentRequests() throws IOException {
        String buildingQueue = "building_queue_" + buildingName;
        channel.basicConsume(buildingQueue, true, this::handleAgentRequest, consumerTag -> {});
    }

    private void handleAgentRequest(String consumerTag, Delivery delivery) {
        String jsonMessage = new String(delivery.getBody(), StandardCharsets.UTF_8);
        try {
            BuildingMessage message = objectMapper.readValue(jsonMessage, BuildingMessage.class);
            switch (message.getType()) {
                case REQUEST_BUILDING_STATUS -> sendStatusToAllAgents();
                case BOOK -> processBookingRequest(message);
                case CANCEL -> processCancellationRequest(message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendStatusToAllAgents() throws IOException {
        BuildingMessage statusMessage = new BuildingMessage();
        statusMessage.setType(MessageType.BUILDING_STATUS);
        statusMessage.setBuildingName(buildingName);
        statusMessage.setAvailableRooms(new ArrayList<>(availableRooms));
        String message = objectMapper.writeValueAsString(statusMessage);
        channel.basicPublish(EXCHANGE_FANOUT, "", null, message.getBytes(StandardCharsets.UTF_8));
    }

    private void processBookingRequest(BuildingMessage request) throws IOException {
        List<Integer> requestedRooms = request.getRequestedRooms();
        if (availableRooms.containsAll(requestedRooms)) {
            requestedRooms.forEach(availableRooms::remove);
        }
        sendStatusToAllAgents();
    }

    private void processCancellationRequest(BuildingMessage request) throws IOException {
        availableRooms.addAll(request.getRequestedRooms());
        sendStatusToAllAgents();
    }
}
