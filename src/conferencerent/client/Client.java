package conferencerent.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.UUID;

import com.rabbitmq.client.*;
import conferencerent.model.ClientRequestMessage;
import conferencerent.model.ClientRequestType;
import org.codehaus.jackson.map.ObjectMapper;

public class Client {
    private static final String clientId = UUID.randomUUID().toString();
    private final static String EXCHANGE_CLIENT = "client_exchange";
    private static final String RESPONSE_QUEUE = "client_response_queue";
    private static Channel channel;
    private static final Object lock = new Object();
    private static boolean responseReceived = false;

    public static void main(String[] args) throws Exception {
        new Client().run();
    }

    public void run() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setUsername("guest");
        factory.setPassword("guest");
        factory.setPort(5672);
        Connection connection = factory.newConnection();
        channel = connection.createChannel();

        channel.exchangeDeclare(EXCHANGE_CLIENT, BuiltinExchangeType.DIRECT);
        channel.queueDeclare(RESPONSE_QUEUE, false, false, false, null);

        startResponseMonitor();
        startBooking();

        channel.close();
        connection.close();
    }

    private void startResponseMonitor() throws Exception {
        channel.basicConsume(RESPONSE_QUEUE, true, (consumerTag, delivery) -> {
            String response = new String(delivery.getBody(), StandardCharsets.UTF_8);

            if (response.startsWith("Available Buildings")) {
                processBuildingList(response);
            } else if (response.startsWith("Your reservations")) {
                try {
                    processReservationList(response);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else if (response.startsWith("Booking confirmed")) {
                System.out.println(response);
            } else if (response.startsWith("Reservation cancelled")) {
                System.out.println(response);
            }
            synchronized (lock) {
                responseReceived = true;
                lock.notify();
            }
        }, consumerTag -> {});
    }

    private void startBooking() throws Exception {
        boolean running = true;
        Scanner scanner = new Scanner(System.in);
        while (running) {
            System.out.println("Conference Room Booking System");
            System.out.println("1. Request list of buildings");
            System.out.println("2. Request your reservations");
            System.out.println("3. Exit");
            System.out.print("Choose an option: ");

            int choice = scanner.nextInt();
            scanner.nextLine();  // Consume newline

            switch (choice) {
                case 1 -> requestListOfBuildings();
                case 2 -> requestListOfReservations();
                case 3 -> {
                    running = false;
                    System.out.println("Exiting the system...");
                }
                default -> System.out.println("Invalid choice. Please try again.");
            }

            synchronized (lock) {
                while (!responseReceived) {
                    lock.wait();
                }
                responseReceived = false;
            }
        }
    }

    private void requestListOfBuildings() throws Exception {
        ClientRequestMessage requestMessage = new ClientRequestMessage(clientId, ClientRequestType.LIST_BUILDINGS);
        ObjectMapper objectMapper = new ObjectMapper();
        String message = objectMapper.writeValueAsString(requestMessage);
        channel.basicPublish(EXCHANGE_CLIENT, "", null, message.getBytes());
    }

    private void processBuildingList(String response) throws IOException {
        Scanner scanner = new Scanner(System.in);
        System.out.println(response);
        System.out.println("1. Book a room");
        System.out.println("2. Exit");
        System.out.print("Choose an option: ");
        int bookingChoice = scanner.nextInt();
        scanner.nextLine();  // Consume newline

        if (bookingChoice == 1) {
            System.out.print("Enter building name: ");
            String building = scanner.nextLine();

            System.out.print("Enter room number: ");
            int room = scanner.nextInt();
            scanner.nextLine();

            ClientRequestMessage bookingRequest = new ClientRequestMessage(clientId, ClientRequestType.BOOK);
            bookingRequest.setBuilding(building);
            bookingRequest.setRoom(room);

            ObjectMapper objectMapper = new ObjectMapper();
            String message = objectMapper.writeValueAsString(bookingRequest);
            channel.basicPublish(EXCHANGE_CLIENT, "", null, message.getBytes());
        }
    }

    private void requestListOfReservations() throws Exception {
        ClientRequestMessage requestMessage = new ClientRequestMessage(clientId, ClientRequestType.LIST_RESERVATIONS);
        ObjectMapper objectMapper = new ObjectMapper();
        String message = objectMapper.writeValueAsString(requestMessage);
        channel.basicPublish(EXCHANGE_CLIENT, "", null, message.getBytes());
    }

    private void processReservationList(String response) throws Exception {
        Scanner scanner = new Scanner(System.in);
        System.out.println(response);

        if (!response.isEmpty()) {
            System.out.println("Enter reservation number to cancel or press Enter to go back:");
            String reservationToCancel = scanner.nextLine();

            if (!reservationToCancel.isEmpty()) {
                cancelReservation(reservationToCancel);
            }
        }
    }

    private void cancelReservation(String reservationNumber) throws Exception {
        ClientRequestMessage cancelRequest = new ClientRequestMessage(clientId, ClientRequestType.CANCEL);
        cancelRequest.setReservationNumber(reservationNumber);

        ObjectMapper objectMapper = new ObjectMapper();
        String message = objectMapper.writeValueAsString(cancelRequest);
        channel.basicPublish(EXCHANGE_CLIENT, "", null, message.getBytes());
        System.out.println("Reservation " + reservationNumber + " has been cancelled.");
    }
}
