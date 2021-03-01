# Traffic Drones exercise

Solution is implemented as actor system as it perfectly fits modeling simulations.
Under the hood, every actor consists of:
 * **Mailbox** - a queue of incoming messages / task / requests / commands
 * **Behaviors** - set of handlers for incoming messages.
 * **Dispatcher** - component responsible for picking tread and use it for picking up a message from the mailbox and execute
   a handler with it.
   
As Mailbox and Dispatcher is provided by akka - behaviors (handlers) are only thing that needs to be written.

Actor System guarantees that only one message is processed by a single actor at the time. This makes concurrency easy to handle.

All messages exchanged between actors are serializable and immutable.

## Actors
There are three actor types
* **Simulation** - Init simulation. Spawn other actors.
* **Dispatcher** - Loads instructions for drones. Sends instructions to drones. Process and store replies from drones.
* **Drone** - Go to requested location. Uses TubeMap to get tube stations around. Check traffic conditions and
  compute own speed. Reports back to dispatcher.

## Run
Run program with SBT from project root
```bash
sbt run
```
