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
import java.util.ArrayList;
import java.util.Scanner;
import java.util.UUID;
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
            System.out.println("Conference Room Booking System");
            System.out.println("1. Request list of buildings");
            System.out.println("2. Request your reservations");
            System.out.println("3. Exit");
            System.out.print("Choose an option: ");

            int choice = scanner.nextInt();
            scanner.nextLine();

            switch (choice) {
                case 1 -> requestListOfBuildings();
                case 2 -> requestListOfReservations();
                case 3 -> running = false;
                default -> System.out.println("Invalid choice. Please try again.");
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
        while (isNewMessageExpected){
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
        System.out.println("Available Buildings: " + response.getBuilding());
        System.out.println("1. Book a room");
        System.out.println("2. Exit");
        System.out.print("Choose an option: ");
        int bookingChoice = scanner.nextInt();
        scanner.nextLine();

        if (bookingChoice == 1) {
            System.out.print("Enter building name: ");
            String building = scanner.nextLine();

            System.out.print("Enter room number: ");
            int room = scanner.nextInt();
            scanner.nextLine();

            try {
                ClientRequestMessage bookingRequest = new ClientRequestMessage(clientId, ClientRequestType.BOOK);
                bookingRequest.setBuilding(building);
                ArrayList<Integer> rooms = new ArrayList<>();
                rooms.add(room);
                bookingRequest.setRoom(rooms);
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
        System.out.printf("Are you sure you want to book rooms %d in building %s? Confirmation number: %s%n",
                response.getRooms().toString(), response.getBuilding(), response.getReservationNumber());

        System.out.println("1. Confirm booking");
        System.out.println("2. Exit");
        System.out.print("Choose an option: ");

        int userChoice = scanner.nextInt();
        scanner.nextLine();

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

        System.out.println("Your reservations:");
        String[] reservations = reservationList.split(",");
        for (int i = 0; i < reservations.length; i++) {
            System.out.printf("%d. Reservation confirmation number: %s%n", i + 1, reservations[i]);
        }

        System.out.println("1. Cancel a reservation");
        System.out.println("2. Exit");
        System.out.print("Choose an option: ");

        int userChoice = scanner.nextInt();
        scanner.nextLine();

        if (userChoice == 1) {
            System.out.print("Enter the number of the reservation you want to cancel: ");
            String reservationToCancel = scanner.next();
            scanner.nextLine();

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
}
