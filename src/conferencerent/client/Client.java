package conferencerent.client;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import conferencerent.model.ClientRequestMessage;
import conferencerent.model.ClientRequestType;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Client {
    private static final String clientId = UUID.randomUUID().toString();
    private final static String EXCHANGE_CLIENT = "client_exchange";
    private static final String AGENT_CLIENT_QUEUE = "agent_to_client_queue";
    private static final String CLIENT_AGENT_QUEUE = "client_to_agent_queue";

    Connection connection;
    private static Channel channel;

    ObjectMapper objectMapper = new ObjectMapper();
    Scanner scanner = new Scanner(System.in);
    private final Lock lock = new ReentrantLock();
    private final Condition newMessageReceived = lock.newCondition();
    private boolean isNewMessage = false;
    private boolean isNewMessageExpected;

    ClientRequestMessage responseMessage = null;

    public static void main(String[] args) throws Exception {
        new Client().run();
    }

    public void run() throws Exception {
        setupChannel();

        startResponseMonitor();
        startBooking();

        channel.close();
        connection.close();
    }

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
        channel.queueDeclare(AGENT_CLIENT_QUEUE, false, false, false, null);
        channel.queueBind(AGENT_CLIENT_QUEUE, EXCHANGE_CLIENT, "agent_to_client");
    }

    private void startBooking() throws Exception {
        boolean running = true;

        while (running) {
            String[] mainMenuOptions = {
                    "1. Request list of buildings",
                    "2. Request your reservations",
                    "3. Exit"
            };
            int choice = displayMenuAndGetChoice("Conference Room Booking System", mainMenuOptions, 1, 3);

            switch (choice) {
                case 1 -> requestListOfBuildings();
                case 2 -> requestListOfReservations();
                case 3 -> running = false;
                default -> System.out.println("Invalid choice. Please try again."); // Should not reach here due to validation
            }
        }
    }

    private void startResponseMonitor() throws Exception {
        channel.basicConsume(AGENT_CLIENT_QUEUE, true, (consumerTag, delivery) -> {
            String jsonResponse = new String(delivery.getBody(), StandardCharsets.UTF_8);
            lock.lock();
            try {
                responseMessage = objectMapper.readValue(jsonResponse, ClientRequestMessage.class);
                isNewMessage = true;
                newMessageReceived.signal();
            } finally {
                lock.unlock();
            }
        }, consumerTag -> {});
    }

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

    private void processBuildingList(ClientRequestMessage response) {
        Map<String, ArrayList<Integer>> buildings = response.getBuildings();
        System.out.println("Available Buildings and Rooms:");
        for (String building : buildings.keySet()) {
            System.out.println("- " + building + " rooms: " + buildings.get(building));
        }

        String[] options = {
                "1. Book rooms",
                "2. Exit"
        };
        int bookingChoice = displayMenuAndGetChoice(null, options, 1, 2);

        if (bookingChoice == 1) {
            String building = getValidBuilding(buildings);
            List<Integer> rooms = getValidRooms(buildings.get(building), building);

            try {
                ClientRequestMessage bookingRequest = new ClientRequestMessage(clientId, ClientRequestType.BOOK);
                Map<String, ArrayList<Integer>> bookingRooms = new HashMap<>();
                bookingRooms.put(building, new ArrayList<>(rooms));
                bookingRequest.setBuildings(bookingRooms);

                String message = objectMapper.writeValueAsString(bookingRequest);
                channel.basicPublish(EXCHANGE_CLIENT, "client_to_agent", null, message.getBytes());
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (bookingChoice == 2) {
            isNewMessageExpected = false;
        }
    }

    private void requestListOfBuildings() throws Exception {
        ClientRequestMessage requestMessage = new ClientRequestMessage(clientId, ClientRequestType.LIST_BUILDINGS);
        String message = objectMapper.writeValueAsString(requestMessage);
        channel.basicPublish(EXCHANGE_CLIENT, "client_to_agent", null, message.getBytes());
        startMessageProcessor();
    }

    private void processBooking(ClientRequestMessage response) {
        String building = response.getBuildingNames().get(0);
        ArrayList<Integer> rooms = response.getRooms(building);
        System.out.printf("Are you sure you want to book room(s) %s in building %s? Confirmation number: %s%n",
                rooms, building, response.getReservationNumber());

        String[] options = {
                "1. Confirm booking",
                "2. Exit"
        };
        int userChoice = displayMenuAndGetChoice(null, options, 1, 2);

        if (userChoice == 1) {
            try {
                ClientRequestMessage confirmMessage = new ClientRequestMessage(clientId, ClientRequestType.CONFIRM);
                confirmMessage.setReservationNumber(response.getReservationNumber());
                String message = objectMapper.writeValueAsString(confirmMessage);

                channel.basicPublish(EXCHANGE_CLIENT, "client_to_agent", null, message.getBytes());
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (userChoice == 2) {
            isNewMessageExpected = false;
        }
    }

    private void processBookingConfirmation(ClientRequestMessage response) {
        System.out.printf("Booking with confirmation number %s has been successfully confirmed.%n",
                response.getReservationNumber());
        isNewMessageExpected = false;
    }

    private void requestListOfReservations() throws Exception {
        ClientRequestMessage requestMessage = new ClientRequestMessage(clientId, ClientRequestType.LIST_RESERVATIONS);
        String message = objectMapper.writeValueAsString(requestMessage);
        channel.basicPublish(EXCHANGE_CLIENT, "client_to_agent", null, message.getBytes());
        startMessageProcessor();
    }

    private void processReservationList(ClientRequestMessage response) throws IOException {
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
        int userChoice = displayMenuAndGetChoice(null, options, 1, 2);

        if (userChoice == 1) {
            String reservationToCancel;
            while (true) {
                System.out.print("Enter the reservation confirmation number you want to cancel: ");
                reservationToCancel = scanner.nextLine();
                if (reservationSet.contains(reservationToCancel)) {
                    break;
                } else {
                    System.out.println("Invalid reservation number. Please enter a valid reservation confirmation number.");
                }
            }

            ClientRequestMessage cancelMessage = new ClientRequestMessage(clientId, ClientRequestType.CANCEL);
            cancelMessage.setReservationNumber(reservationToCancel);
            String message = objectMapper.writeValueAsString(cancelMessage);
            channel.basicPublish(EXCHANGE_CLIENT, "client_to_agent", null, message.getBytes());
        } else if (userChoice == 2) {
            isNewMessageExpected = false;
        }
    }

    private void processCancelReservation(ClientRequestMessage response) {
        System.out.printf("Reservation with confirmation number %s has been successfully cancelled.%n",
                response.getReservationNumber());
        isNewMessageExpected = false;
    }

    private void processErrorMessage(ClientRequestMessage response) {
        String errorMessage = response.getErrorMessage();
        if (errorMessage != null && !errorMessage.isEmpty()) {
            System.out.printf("Error: %s%n", errorMessage);
        } else {
            System.out.println("An unknown error occurred.");
        }
        isNewMessageExpected = false;
    }

    // Helper method to display a menu and get user's choice
    private int displayMenuAndGetChoice(String header, String[] options, int min, int max) {
        if (header != null) {
            System.out.println(header);
        }
        for (String option : options) {
            System.out.println(option);
        }
        System.out.print("Choose an option: ");
        return readIntInput(min, max);
    }

    // Helper method to read integer input within a range
    private int readIntInput(int min, int max) {
        int choice;
        while (true) {
            try {
                choice = Integer.parseInt(scanner.nextLine());
                if (choice >= min && choice <= max) {
                    break;
                } else {
                    System.out.printf("Please enter a number between %d and %d.%n", min, max);
                    System.out.print("Choose an option: ");
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a number.");
                System.out.print("Choose an option: ");
            }
        }
        return choice;
    }

    // Overloaded method to include custom prompt
    private int readIntInput(String prompt, int min, int max) {
        int choice;
        while (true) {
            try {
                System.out.print(prompt);
                choice = Integer.parseInt(scanner.nextLine());
                if (choice >= min && choice <= max) {
                    break;
                } else {
                    System.out.printf("Please enter a number between %d and %d.%n", min, max);
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a number.");
            }
        }
        return choice;
    }

    // Helper method to get a valid building from the user
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

    // Helper method to get valid room numbers from the user
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
                System.out.println("Please enter valid room numbers from the available rooms in " + building + ": " + availableRooms);
            }
        }
        return rooms;
    }
}