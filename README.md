# ConferenceRent.com - Distributed Room Booking System

## Project Description

A distributed conference room booking system implemented in Java using RabbitMQ for inter-process communication. The system allows clients to book meeting rooms in various buildings through intermediary rental agents.

## System Architecture

The system consists of three primary types of processes:

### 1. **Clients**
- Initiate requests for building lists, bookings, confirmations, and cancellations
- Do not communicate directly with buildings - all interactions go through agents
- Are unaware of which specific agent they are communicating with

### 2. **Rental Agents**
- Process client requests
- Maintain records of reservations and unconfirmed bookings
- Communicate with buildings to update room availability
- Synchronize booking data with other agents to ensure consistency across the system

### 3. **Buildings**
- Manage the availability of conference rooms
- Process booking and cancellation requests from rental agents
- Can be dynamically added to the system at runtime

## Communication Mechanism

### Exchanges and Queues:

- **client_exchange** (DIRECT) - for client-agent interaction
- **direct_exchange** (DIRECT) - for directed agent-building messages
- **building_announce_exchange** (FANOUT) - for broadcast messages from buildings
- **replication_exchange** (FANOUT) - for data replication between agents

### Data Flows:

1. **Client → Agent**: via `client_exchange` with routing key "client_to_agent"
2. **Agent → Client**: via `client_exchange` with routing key = `clientId`
3. **Agent → Building**: via `direct_exchange` with routing key = `buildingName`
4. **Building → Agents**: via `building_announce_exchange` (broadcast)
5. **Agent → Agents**: via `replication_exchange` (booking synchronization)

## Key Features

### Dynamic Building Connection
- New buildings can be connected "on-the-fly"
- Upon connection, buildings broadcast their status to all agents
- Agents store building information with timestamps
- Inactive buildings are automatically removed from the available list

### Multiple Agents
- The system supports multiple agents running simultaneously
- When a new agent connects:
  1. Requests status from all buildings
  2. Receives booking information from other agents
  3. Starts accepting client requests
- All agents synchronize booking data

### Data Consistency
- Two-phase booking process (reservation + confirmation)
- Replication of unconfirmed and confirmed bookings between agents
- Room availability verification before final confirmation

## Functionality

The system supports the following operations:

1. **Request building list** - client receives a list of all available buildings and rooms
2. **Book rooms** - client can book one or more rooms in a building
3. **Receive reservation number** - when rooms are available, client receives a unique number
4. **Unavailability notification** - error message when requested rooms are not available
5. **Confirm booking** - two-step process using the reservation number
6. **Cancel reservation** - using the reservation number
7. **Dynamic building addition** - buildings can be connected while the system is running
8. **Multiple agents** - support for multiple agents running simultaneously
9. **Error handling** - proper handling of incorrect situations (e.g., invalid reservation number)

## Project Structure

```
src/conferencerent/
├── agent/
│   └── Agent.java          # Rental agent intermediary
├── building/
│   └── Building.java       # Building with conference rooms
├── client/
│   └── Client.java         # Client application
└── model/
    ├── BuildingMessage.java    # Messages for buildings
    ├── ClientMessage.java      # Messages for clients
    └── MessageType.java        # Message types
```

## Requirements

- Java 11+
- RabbitMQ Server
- Jackson (for JSON serialization)
- RabbitMQ Java Client

## Running the System

### 1. Start RabbitMQ
```bash
# Ensure RabbitMQ is running on localhost:5672
```

### 2. Start Buildings
```bash
java conferencerent.building.Building
# Creates 3 buildings: Building O, Building R, Building D
```

### 3. Start Agent (can run multiple instances)
```bash
java conferencerent.agent.Agent
```

### 4. Start Client
```bash
java conferencerent.client.Client
```

## Usage

After starting the client, a menu is available:

```
Conference Room Booking System
1. Request list of buildings
2. Request your reservations
3. Exit
```

### Booking Process:
1. Request building list
2. Select building and rooms
3. Receive reservation number
4. Confirm booking

### Cancellation Process:
1. Request your reservations list
2. Enter reservation number to cancel

## Implementation Details

### Agent (Agent.java)
- Manages unconfirmed bookings (`unconfirmedReservations`)
- Manages confirmed reservations (`reservations`)
- Tracks room availability (`availableRooms`)
- Timeouts for detecting inactive buildings (1000ms)
- Synchronization between agents via replication

### Building (Building.java)
- Set of available rooms (`availableRooms`)
- Processes booking and cancellation requests
- Broadcasts status updates to all agents
- Thread-safe room operations

### Client (Client.java)
- Asynchronous response reception from agent
- Interactive console menu
- User input validation
- Unique identifier for each client
