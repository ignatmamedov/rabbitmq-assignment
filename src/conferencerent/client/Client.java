package conferencerent.client;

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
        Channel channel = connection.createChannel();

        // Declare the exchange and the queue
        channel.exchangeDeclare(EXCHANGE_CLIENT, BuiltinExchangeType.DIRECT);

        startBooking(channel);

        channel.close();
        connection.close();
    }

    private void startBooking(Channel channel) throws Exception {
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
                case 1 -> requestListOfBuildings(channel);
                case 2 -> requestListOfReservations(channel);
                case 3 -> {
                    running = false;
                    System.out.println("Exiting the system...");
                }
                default -> System.out.println("Invalid choice. Please try again.");
            }
        }
    }

    private static void requestListOfBuildings(Channel channel) throws Exception {
        ClientRequestMessage requestMessage = new ClientRequestMessage(clientId, ClientRequestType.LIST);

        ObjectMapper objectMapper = new ObjectMapper();
        String message = objectMapper.writeValueAsString(requestMessage);

        channel.basicPublish(EXCHANGE_CLIENT, "", null, message.getBytes());

        channel.basicConsume(RESPONSE_QUEUE, true, (consumerTag, delivery) -> {
            String response = new String(delivery.getBody(), StandardCharsets.UTF_8);
            System.out.println("Available Buildings and Rooms: " + response);

            try {
                processBuildingList(channel, response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, consumerTag -> {});
    }

    private static void processBuildingList(Channel channel, String response) throws Exception {
        Scanner scanner = new Scanner(System.in);
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

            channel.basicConsume(RESPONSE_QUEUE, true, (consumerTag, delivery) -> {
                String bookingResponse = new String(delivery.getBody(), StandardCharsets.UTF_8);
                System.out.println(bookingResponse);

                if (bookingResponse.startsWith("Booking confirmed")) {
                    System.out.print("Enter reservation number to confirm booking: ");
                    String reservationNumber = scanner.nextLine();

                    try {
                        confirmBooking(channel, reservationNumber);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }, consumerTag -> {});
        }
    }
    private static void confirmBooking(Channel channel, String reservationNumber) throws Exception {
        ClientRequestMessage confirmRequest = new ClientRequestMessage(clientId, ClientRequestType.CONFIRM);
        confirmRequest.setReservationNumber(reservationNumber);

        ObjectMapper objectMapper = new ObjectMapper();
        String message = objectMapper.writeValueAsString(confirmRequest);

        channel.basicPublish(EXCHANGE_CLIENT, "", null, message.getBytes());
        System.out.println("Booking confirmed with reservation number: " + reservationNumber);
    }

    private static void requestListOfReservations(Channel channel) throws Exception {
        // Create a request for the client's reservations
        ClientRequestMessage requestMessage = new ClientRequestMessage(clientId, ClientRequestType.LIST_RESERVATIONS);

        ObjectMapper objectMapper = new ObjectMapper();
        String message = objectMapper.writeValueAsString(requestMessage);

        // Publish the request
        channel.basicPublish(EXCHANGE_CLIENT, "", null, message.getBytes());

        // Wait for the agent's response with the reservations
        channel.basicConsume(RESPONSE_QUEUE, true, (consumerTag, delivery) -> {
            String reservations = new String(delivery.getBody(), StandardCharsets.UTF_8);
            System.out.println("Your reservations: " + reservations);

            // Prompt for canceling a reservation
            try {
                processReservationCancellation(channel, reservations);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, consumerTag -> {});
    }


    private static void processReservationCancellation(Channel channel, String reservations) throws Exception {
        Scanner scanner = new Scanner(System.in);

        if (!reservations.isEmpty()) {
            System.out.println("Enter reservation number to cancel or press Enter to go back:");
            String reservationToCancel = scanner.nextLine();

            if (!reservationToCancel.isEmpty()) {
                cancelReservation(channel, reservationToCancel);
            }
        }
    }

    private static void cancelReservation(Channel channel, String reservationNumber) throws Exception {
        // Create a cancellation request message
        ClientRequestMessage cancelRequest = new ClientRequestMessage(clientId, ClientRequestType.CANCEL);
        cancelRequest.setReservationNumber(reservationNumber);

        ObjectMapper objectMapper = new ObjectMapper();
        String message = objectMapper.writeValueAsString(cancelRequest);

        // Publish the cancellation request
        channel.basicPublish(EXCHANGE_CLIENT, "", null, message.getBytes());
        System.out.println("Reservation " + reservationNumber + " has been cancelled.");
    }
}
