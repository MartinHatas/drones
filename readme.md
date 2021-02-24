# Traffic Drones exercise

Solution is implemented as actor system as it perfectly fits modeling simulations.
Under the hood, every actor consists of:
 * **Mailbox** - a queue of incoming messages / task / requests / commands
 * **Behaviors** - set of handlers for incoming messages
 * **Dispatcher** - component responsible for picking tread and use it for picking up a message from the mailbox and execute
   a handler with it  
   
As Mailbox and Dispatcher is provided by akka - behaviors (handlers) are only thing that needs to be written.

Actor System uses event loop rather than standard threading model to achieve concurency and parallelism. This assure
that a handler of an actor will be called by at most one thread at the same time so I don't have to care about
thread safety like synchronizations, locks or atomic or volatile objects.

All messages exchanged between actors are serializable and immutable.
