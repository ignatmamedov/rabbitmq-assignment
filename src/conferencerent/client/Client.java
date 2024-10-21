package conferencerent.client;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import conferencerent.model.ClientMessage;
import conferencerent.model.MessageType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The Client class allows users to interact with the booking system.
 * Users can request building lists, make reservations, confirm bookings,
 * and cancel reservations.
 */
public class Client {
    private final String clientId = UUID.randomUUID().toString();
    private final static String EXCHANGE_CLIENT = "client_exchange";
    private static final String CLIENT_AGENT_QUEUE = "client_to_agent_queue";

    Connection connection;
    private static Channel channel;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Scanner scanner = new Scanner(System.in);
    private final Lock lock = new ReentrantLock();
    private final Condition newMessageReceived = lock.newCondition();
    private boolean isNewMessage = false;
    private boolean isNewMessageExpected;

    ClientMessage responseMessage = null;

    /**
     * The main method to run the client application.
     *
     * @param args command-line arguments
     * @throws Exception if an error occurs during execution
     */
    public static void main(String[] args) throws Exception {
        new Client().run();
    }

    /**
     * Runs the client by setting up connections and starting the booking process.
     *
     * @throws Exception if an error occurs during execution
     */
    public void run() throws Exception {
        setupChannel();

        startResponseMonitor();
        startBooking();

        channel.close();
        connection.close();
    }

    /**
     * Sets up the RabbitMQ channel and declares necessary exchanges and queues.
     *
     * @throws IOException      if an error occurs during setup
     * @throws TimeoutException if a timeout occurs during connection
     */
    private void setupChannel() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setUsername("guest");
        factory.setPassword("guest");
        factory.setPort(5672);
        connection = factory.newConnection();
        channel = connection.createChannel();
        channel.exchangeDeclare(EXCHANGE_CLIENT, BuiltinExchangeType.DIRECT);

        channel.queueDeclare(CLIENT_AGENT_QUEUE, false, false, false, null);

        String clientQueueName = "agent_to_client_queue_" + clientId;
        channel.queueDeclare(clientQueueName, false, false, true, null);
        channel.queueBind(clientQueueName, EXCHANGE_CLIENT, clientId);
    }

    /**
     * Starts the booking process by displaying menus and handling user input.
     *
     * @throws Exception if an error occurs during execution
     */
    private void startBooking() throws Exception {
        boolean running = true;

        while (running) {
            String[] mainMenuOptions = {
                    "1. Request list of buildings",
                    "2. Request your reservations",
                    "3. Exit"
            };
            int choice = displayMenuAndGetChoice(
                    "Conference Room Booking System", mainMenuOptions, 3
            );

            switch (choice) {
                case 1 -> requestListOfBuildings();
                case 2 -> requestListOfReservations();
                case 3 -> running = false;
                default -> System.out.println("Invalid choice. Please try again.");
            }
        }
    }

    /**
     * Starts a thread to monitor responses from the agent.
     *
     * @throws Exception if an error occurs during setup
     */
    private void startResponseMonitor() throws Exception {
        String clientQueueName = "agent_to_client_queue_" + clientId;
        channel.basicConsume(clientQueueName, true, (consumerTag, delivery) -> {
            String jsonResponse = new String(delivery.getBody(), StandardCharsets.UTF_8);
            lock.lock();
            try {
                responseMessage = objectMapper.readValue(jsonResponse, ClientMessage.class);
                isNewMessage = true;
                newMessageReceived.signal();
            } finally {
                lock.unlock();
            }
        }, consumerTag -> {});
    }

    /**
     * Starts processing incoming messages from the agent.
     *
     * @throws Exception if an error occurs during processing
     */
    private void startMessageProcessor() throws Exception {
        isNewMessageExpected = true;
        while (isNewMessageExpected) {
            waitResponse();
            switch (responseMessage.getType()) {
                case LIST_BUILDINGS -> processBuildingList(responseMessage);
                case LIST_RESERVATIONS -> processReservationList(responseMessage);
                case BOOK -> processBooking(responseMessage);
                case CONFIRM -> processBookingConfirmation(responseMessage);
                case CANCEL -> processCancelReservation(responseMessage);
                case ERROR -> processErrorMessage(responseMessage);
                default -> {
                    System.out.println("Unknown response type");
                    isNewMessageExpected = false;
                }
            }
        }
    }

    /**
     * Waits for a response message from the agent.
     *
     * @throws Exception if an error occurs during waiting
     */
    private void waitResponse() throws Exception {
        lock.lock();
        try {
            while (!isNewMessage) {
                newMessageReceived.await();
            }
            isNewMessage = false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Processes the building list received from the agent.
     *
     * @param response the response message containing the building list
     */
    private void processBuildingList(ClientMessage response) {
        Map<String, ArrayList<Integer>> buildings = response.getBuildings();
        System.out.println("Available Buildings and Rooms:");
        for (String building : buildings.keySet()) {
            System.out.println("- " + building + " rooms: " + buildings.get(building));
        }

        String[] options = {
                "1. Book rooms",
                "2. Exit"
        };
        int bookingChoice = displayMenuAndGetChoice(null, options, 2);

        if (bookingChoice == 1) {
            String building = getValidBuilding(buildings);
            List<Integer> rooms = getValidRooms(buildings.get(building), building);

            try {
                ClientMessage bookingRequest = new ClientMessage(clientId, MessageType.BOOK);
                Map<String, ArrayList<Integer>> bookingRooms = new HashMap<>();
                bookingRooms.put(building, new ArrayList<>(rooms));
                bookingRequest.setBuildings(bookingRooms);

                sendMessageToAgent(bookingRequest);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (bookingChoice == 2) {
            isNewMessageExpected = false;
        }
    }

    /**
     * Requests the list of buildings from the agent.
     *
     * @throws Exception if an error occurs during sending
     */
    private void requestListOfBuildings() throws Exception {
        ClientMessage requestMessage = new ClientMessage(clientId, MessageType.LIST_BUILDINGS);
        sendMessageToAgent(requestMessage);
        startMessageProcessor();
    }

    /**
     * Processes the booking response from the agent.
     *
     * @param response the response message containing the booking details
     */
    private void processBooking(ClientMessage response) {
        String building = response.getBuildingNames().get(0);
        ArrayList<Integer> rooms = response.getRooms(building);
        System.out.printf("Are you sure you want to book room(s) %s in building %s? Confirmation number: %s%n",
                rooms, building, response.getReservationNumber());

        String[] options = {
                "1. Confirm booking",
                "2. Exit"
        };
        int userChoice = displayMenuAndGetChoice(null, options, 2);

        if (userChoice == 1) {
            try {
                ClientMessage confirmMessage = new ClientMessage(clientId, MessageType.CONFIRM);
                confirmMessage.setReservationNumber(response.getReservationNumber());

                sendMessageToAgent(confirmMessage);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (userChoice == 2) {
            isNewMessageExpected = false;
        }
    }

    /**
     * Processes the booking confirmation from the agent.
     *
     * @param response the response message confirming the booking
     */
    private void processBookingConfirmation(ClientMessage response) {
        System.out.printf("Booking with confirmation number %s has been successfully confirmed.%n",
                response.getReservationNumber());
        isNewMessageExpected = false;
    }

    /**
     * Requests the list of reservations from the agent.
     *
     * @throws Exception if an error occurs during sending
     */
    private void requestListOfReservations() throws Exception {
        ClientMessage requestMessage = new ClientMessage(clientId, MessageType.LIST_RESERVATIONS);
        sendMessageToAgent(requestMessage);
        startMessageProcessor();
    }

    /**
     * Processes the reservation list received from the agent.
     *
     * @param response the response message containing the reservation list
     * @throws IOException if an error occurs during processing
     */
    private void processReservationList(ClientMessage response) throws IOException {
        String reservationList = response.getReservationNumber();
        if (reservationList == null || reservationList.isEmpty()) {
            System.out.println("You have no reservations.");
            isNewMessageExpected = false;
            return;
        }

        String[] reservations = reservationList.split(",");
        Set<String> reservationSet = new HashSet<>(Arrays.asList(reservations));

        System.out.println("Your reservations:");
        for (String res : reservations) {
            System.out.printf("- Reservation confirmation number: %s%n", res);
        }

        String[] options = {
                "1. Cancel a reservation",
                "2. Exit"
        };
        int userChoice = displayMenuAndGetChoice(null, options, 2);

        if (userChoice == 1) {
            String reservationToCancel;
            while (true) {
                System.out.print("Enter the reservation confirmation number you want to cancel: ");
                reservationToCancel = scanner.nextLine();
                if (reservationSet.contains(reservationToCancel)) {
                    break;
                } else {
                    System.out.println(
                            "Invalid reservation number. Please enter a valid reservation confirmation number."
                    );
                }
            }

            ClientMessage cancelMessage = new ClientMessage(clientId, MessageType.CANCEL);
            cancelMessage.setReservationNumber(reservationToCancel);
            sendMessageToAgent(cancelMessage);
        } else if (userChoice == 2) {
            isNewMessageExpected = false;
        }
    }

    /**
     * Processes the cancellation confirmation from the agent.
     *
     * @param response the response message confirming the cancellation
     */
    private void processCancelReservation(ClientMessage response) {
        System.out.printf("Reservation with confirmation number %s has been successfully cancelled.%n",
                response.getReservationNumber());
        isNewMessageExpected = false;
    }

    /**
     * Processes an error message received from the agent.
     *
     * @param response the response message containing the error
     */
    private void processErrorMessage(ClientMessage response) {
        String errorMessage = response.getErrorMessage();
        if (errorMessage != null && !errorMessage.isEmpty()) {
            System.out.printf("Error: %s%n", errorMessage);
        } else {
            System.out.println("An unknown error occurred.");
        }
        isNewMessageExpected = false;
    }

    /**
     * Sends a message to the agent.
     *
     * @param requestMessage the message to send
     * @throws IOException if an error occurs during publishing
     */
    private void sendMessageToAgent(ClientMessage requestMessage) throws IOException {
        String message = objectMapper.writeValueAsString(requestMessage);
        channel.basicPublish(
                EXCHANGE_CLIENT, "client_to_agent", null, message.getBytes(
                        StandardCharsets.UTF_8
                )
        );
    }

    /**
     * Displays a menu and gets the user's choice.
     *
     * @param header the header message
     * @param options the menu options
     * @param max the maximum valid choice
     * @return the user's choice
     */
    private int displayMenuAndGetChoice(String header, String[] options, int max) {
        if (header != null) {
            System.out.println(header);
        }
        for (String option : options) {
            System.out.println(option);
        }
        System.out.print("Choose an option: ");
        return readIntInput(max);
    }

    /**
     * Reads an integer input from the user.
     *
     * @param max the maximum valid number
     * @return the integer input
     */
    private int readIntInput(int max) {
        int choice;
        while (true) {
            try {
                choice = Integer.parseInt(scanner.nextLine());
                if (choice >= 1 && choice <= max) {
                    break;
                } else {
                    System.out.printf("Please enter a number between %d and %d.%n", 1, max);
                    System.out.print("Choose an option: ");
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a number.");
                System.out.print("Choose an option: ");
            }
        }
        return choice;
    }

    /**
     * Gets a valid building name from the user.
     *
     * @param buildings the map of available buildings and rooms
     * @return the valid building name
     */
    private String getValidBuilding(Map<String, ArrayList<Integer>> buildings) {
        String building;
        while (true) {
            System.out.print("Enter building name: ");
            building = scanner.nextLine();
            if (buildings.containsKey(building)) {
                break;
            } else {
                System.out.println("Invalid building name. Please choose from the available buildings.");
            }
        }
        return building;
    }

    /**
     * Gets a list of valid room numbers from the user.
     *
     * @param availableRooms the list of available rooms in the building
     * @param building the building name
     * @return the list of valid room numbers
     */
    private List<Integer> getValidRooms(ArrayList<Integer> availableRooms, String building) {
        List<Integer> rooms = new ArrayList<>();
        while (true) {
            System.out.print("Enter room numbers separated by commas: ");
            String input = scanner.nextLine();
            String[] roomStrings = input.split(",");
            boolean allValid = true;
            rooms.clear();

            for (String roomStr : roomStrings) {
                try {
                    int room = Integer.parseInt(roomStr.trim());
                    if (availableRooms.contains(room)) {
                        rooms.add(room);
                    } else {
                        System.out.printf("Room number %d is not available in %s.%n", room, building);
                        allValid = false;
                        break;
                    }
                } catch (NumberFormatException e) {
                    System.out.printf("Invalid room number: %s%n", roomStr.trim());
                    allValid = false;
                    break;
                }
            }

            if (allValid && !rooms.isEmpty()) {
                break;
            } else {
                System.out.println(
                        "Please enter valid room numbers from the available rooms in "
                                + building
                                + ": "
                                + availableRooms
                );
            }
        }
        return rooms;
    }
}