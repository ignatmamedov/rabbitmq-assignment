package conferencerent.building;

import com.rabbitmq.client.*;
import conferencerent.model.BuildingMessage;
import conferencerent.model.MessageType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The Building class represents a conference building with rooms.
 * It handles booking and cancellation requests from the agent
 * and keeps track of available rooms.
 */
public class Building {
    private static final String EXCHANGE_DIRECT = "direct_exchange";
    private static final String EXCHANGE_FANOUT = "building_announce_exchange";
    private final String buildingName;
    private final Set<Integer> availableRooms;
    private Channel channel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Constructs a Building instance with a name and a set of rooms.
     *
     * @param buildingName the name of the building
     * @param rooms        the set of room numbers available in the building
     */
    public Building(String buildingName, Set<Integer> rooms) {
        this.buildingName = buildingName;
        this.availableRooms = Collections.synchronizedSet(new HashSet<>(rooms));
    }

    /**
     * The main method to run multiple building instances.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        List<Building> buildings = Arrays.asList(
                new Building("Building O", Set.of(101, 102, 103)),
                new Building("Building R", Set.of(201, 202, 203)),
                new Building("Building D", Set.of(301, 302, 303))
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

    /**
     * Runs the building by setting up connections, announcing its status,
     * and handling requests from the agent.
     *
     * @throws Exception if an error occurs during execution
     */
    public void run() throws Exception {
        setupConnection();
        announceBuilding();
        listenForAgentRequests();
    }

    /**
     * Sets up the RabbitMQ connections and declares necessary exchanges and queues.
     *
     * @throws Exception if an error occurs during setup
     */
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

    /**
     * Announces the building's status to all agents.
     *
     * @throws IOException if an error occurs during publishing
     */
    private void announceBuilding() throws IOException {
        sendStatusToAllAgents();
    }

    /**
     * Starts listening for requests from agents.
     *
     * @throws IOException if an error occurs during consuming messages
     */
    private void listenForAgentRequests() throws IOException {
        String buildingQueue = "building_queue_" + buildingName;
        channel.basicConsume(buildingQueue, true, this::handleAgentRequest, consumerTag -> {});
    }

    /**
     * Handles incoming requests from agents and processes them accordingly.
     *
     * @param consumerTag the consumer tag
     * @param delivery    the message delivery
     */
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

    /**
     * Sends the current status of the building to all agents.
     *
     * @throws IOException if an error occurs during publishing
     */
    private void sendStatusToAllAgents() throws IOException {
        BuildingMessage statusMessage = new BuildingMessage();
        statusMessage.setType(MessageType.BUILDING_STATUS);
        statusMessage.setBuildingName(buildingName);
        statusMessage.setAvailableRooms(new ArrayList<>(availableRooms));
        String message = objectMapper.writeValueAsString(statusMessage);
        channel.basicPublish(EXCHANGE_FANOUT, "", null, message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Processes a booking request from the agent.
     *
     * @param request the booking request message
     * @throws IOException if an error occurs during processing
     */
    private void processBookingRequest(BuildingMessage request) throws IOException {
        List<Integer> requestedRooms = request.getRequestedRooms();
        if (availableRooms.containsAll(requestedRooms)) {
            requestedRooms.forEach(availableRooms::remove);
        }
        sendStatusToAllAgents();
    }

    /**
     * Processes a cancellation request from the agent.
     *
     * @param request the cancellation request message
     * @throws IOException if an error occurs during processing
     */
    private void processCancellationRequest(BuildingMessage request) throws IOException {
        availableRooms.addAll(request.getRequestedRooms());
        sendStatusToAllAgents();
    }
}
