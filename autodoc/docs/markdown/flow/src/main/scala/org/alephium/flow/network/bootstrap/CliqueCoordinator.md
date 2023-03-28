[View code on GitHub](https://github.com/alephium/alephium/blob/master/flow/src/main/scala/org/alephium/flow/network/bootstrap/CliqueCoordinator.scala)

The `CliqueCoordinator` class is part of the `alephium` project and is responsible for coordinating the connection between different brokers in a clique. A clique is a group of brokers that are connected to each other and share information about the network. 

The `CliqueCoordinator` class extends the `BaseActor` class and has three states: `awaitBrokers`, `awaitAck`, and `awaitTerminated`. The `awaitBrokers` state is the initial state and waits for brokers to connect to the coordinator. When a broker connects, the coordinator creates a new `BrokerConnector` actor to handle the connection and adds the broker's information to its state. Once the coordinator has received information from all the brokers in the clique, it broadcasts the clique information to all the brokers and transitions to the `awaitAck` state.

In the `awaitAck` state, the coordinator waits for acknowledgments from all the brokers that they have received the clique information. Once all the brokers have acknowledged, the coordinator broadcasts a `Ready` message to all the brokers and transitions to the `awaitTerminated` state.

In the `awaitTerminated` state, the coordinator waits for all the `BrokerConnector` actors to terminate. Once all the actors have terminated, the coordinator sends the clique information to the `Bootstrapper` actor and stops itself.

The `CliqueCoordinator` class has a companion object that defines the `props` method and the `Ready` event. The `props` method creates a new instance of the `CliqueCoordinator` class with the given `bootstrapper`, `privateKey`, and `publicKey`. The `Ready` event is a case object that represents the clique being ready. It has a custom `Serde` implementation that maps the `Ready` object to the integer value 0.

Overall, the `CliqueCoordinator` class plays an important role in the `alephium` project by coordinating the connection between brokers in a clique and ensuring that all the brokers have the same information about the network.
## Questions: 
 1. What is the purpose of the `CliqueCoordinator` class and how does it fit into the overall `alephium` project?
- The `CliqueCoordinator` class is responsible for coordinating the connection and communication between brokers in the network. It is part of the `org.alephium.flow.network.bootstrap` package, which is likely related to the network bootstrapping process of the project.

2. What is the `Ready` event and how is it serialized/deserialized?
- The `Ready` event is a case object that represents the readiness of the brokers in the network. It is serialized/deserialized using a custom `Serde` implementation that maps the integer value 0 to the `Ready` object.

3. What is the purpose of the `awaitTerminated` method and what happens when all brokers are closed?
- The `awaitTerminated` method is called when a broker actor is terminated. It sets the actor as closed and checks if all brokers are closed. When all brokers are closed, the method sends the `IntraCliqueInfo` to the `bootstrapper` actor and stops the `CliqueCoordinator` actor.