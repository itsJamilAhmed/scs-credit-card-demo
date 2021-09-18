# Spring Cloud Stream Demo: Credit Card Fraud Checking

## Repository Purpose

> "While the concept of *publish-subscribe messaging* is not new, Spring Cloud Stream takes the extra step of making it an **opinionated choice** for its application model."

The above is an extract from the Spring Cloud Stream documentation [here](https://docs.spring.io/spring-cloud-stream/docs/current/reference/html/spring-cloud-stream.html#spring-cloud-stream-overview-persistent-publish-subscribe-support). While I am all in favour of the pub-sub pattern for application integration, it is difficult for many use-cases to completely shed themselves of the [request-reply](https://en.wikipedia.org/wiki/Request%E2%80%93response) interactions that exist in the overall application architecture.

Messaging middleware solutions such as the [Solace PubSub+ Event Broker](https://solace.com/products/event-broker/) recognise this requirement and have the API natively support both publish-subscribe and point-to-point (i.e. request-reply) styles of interaction with equal importance.

This repository will provide a working sample in the context of a hypothetical use-case in financial services. The use-case implements event-driven microservices architecture in the backend, which are supporting an externally facing API offered over RESTful HTTP. The event-driven processing pipeline is essentially triggered by a synchronous HTTP operation, which becomes the request message that demands a corresponding response message from the end of the event-driven processing pipeline.

### Implementing request-reply with Spring Cloud Stream
Two important constructs enable request-reply communication to take place over common messaging middleware:
1. The use of a dynamically created 'inbox' or 'reply-to' topics to allow messages carrying the response to be routed precisely to the original requesting service
   * Metadata carried within the message itself allows a responding service to know how to reply specifically to this dynamic topic
1. The insertion of correlation IDs on the outbound request messages that are present again on the replies
   * These allow multiple requests to be active in parallel, with responses being matched up as they arrive asynchronously

While Spring Cloud Stream has no direct support to create a specialised 'request' message that automatically handles the mechanics of reply-to topics and correlation IDs, we can of course use the building blocks of message *headers* and the setting of *target destination* to achieve a successful request-reply interaction between two microservices.

Note: The [Microgateway](https://docs.solace.com/Overviews/Microgateway-Concepts/Microgateway-Use-Cases.htm) feature in the Solace PubSub+ broker will handle the protocol mediation, essentially converting each HTTP operation to a *request* message, which contains the reply-to topic the final response message needs to target.

### Use-Case Information: Credit Card Fraud Check

Our hypothetical use-case involves a bank that provides co-branded credit cards and services to *partner* organisations that are not banks. e.g. a large grocery retailer issuing credit cards for their customers.

The partner organisation is able to call a RESTful API (hosted by the bank) to check if a credit card is suspected of fraudulent usage. If yes, the partner also has the option to request the card be blocked from further use, or still remain active. (The partner may decide to block the card in a different API call following some additional checks for example.)

While externally the HTTP API appears simple to the partner, inside the bank numerous microservices are involved to support its interaction like so:
![Image of High Level Architecture](https://github.com/itsJamilAhmed/scs-credit-card-demo/blob/main/images/Fraud-Check-High-Level-Architecture.jpg)

A high-level flow is as follows:
1. For a given credit card, a list of recent transactions needs to be fetched from the Transactions Service
2. Those recent transactions need to be provided to the Fraud Detection Service to analyse. (e.g. The amounts, retailers and locations can input towards the analysis.)
3. If fraudulent use is suspected, and the partner requested a card block, a request is sent to the Card Service
4. Finally a response message is provided back to the original API call

## Services in this repository

Service | Function |
---- | -------- |
fraudCheck Mediator | Mediate the translation from the externally facing HTTP API to the internal event-driven architecture and its topic taxonomy. It is also a suitable location to validate the request payload as being correct and 'fail-fast' to the caller if needed. |
fraudCheck Error Handling | This service generically receives any error messages from the internal services, to then produce a final, externally-suitable response message back to the caller. |
fraudCheck Orchestrator | This service holds the logic to orchestrate all the necessary internal services to support the externally facing 'fraudCheck' API. It is envisioned that there is a corresponding Mediator, Error Handler and Orchestrator Service for each of the externally presented APIs. |
Transactions History | This is a simple service that returns the requested number of recent transactions for a given card number. It can generically support flows in addition to the fraudCheck pipeline here. |
Fraud Detection | This is a simple service that takes some supplied transactions and returns a boolean state of whether fraud is detected. |
Card Block | This is a simple service that takes a supplied card number and sets it to the requested block or not-blocked state. |


### Implementation Principles

1. Each service is fully stateless and the 'Event Carried State Transfer' pattern is used to achieve this
    * A service can therefore terminate and return to immediately resume processing
    * Headers on the created events are used to carry state that services in a later part of the pipeline may require
    * An example is whether a card block is desired in the event of fraud being detected. This forks the processing pipeline and so a necessary piece of state to transfer.  
1. Each service can be scaled up and down in a targeted fashion
    * The inbound channel for each service is a non-exclusive queue with the appropriate topic subscription
    * This means multiple instances of the service can be safely deployed to compete for consumption of outstanding messages on the channel
1. Deferred execution is used where possible to minimise latency for the API caller.
    * An example is the request to block a card if fraud is detected and a block desired.
    * The orchestrator service executes in parallel the request to block the card, and the generation of the final response message to the API caller.
    * In other words, it assumes that the caller does not need to wait until the card has actually been blocked before it can be informed of the fraud detection status.

## Running the services

### Public Access Event Broker

The configuration files (`application.yaml`) for each of the services uses a PubSub+ Event Broker available for public access and hosted in [Solace Cloud](https://solace.com/products/event-broker/cloud/). This means you are able to checkout the project and run it without needing to [setup an Event Broker of your own first](https://www.solace.dev/). Your running instances of the service will automatically join the competitive consumption against any other instance already running by someone else.

If you want to instead leverage your own broker instance, please update the `spring > cloud > stream > binders > solace-broker` section of each `application.yaml` configuration file first. 

### Step 1. Start the Mediator and Error Handling Service

We will start with a minimal deployment of these two services first. It will demonstrate the ability of one service to receive the HTTP operation as a message, and another separate service to handle an error with the request and produce a response back to the API caller.

Clone this GitHub repository containing all the services to a suitable location:

```
git clone https://github.com/itsJamilAhmed/scs-credit-card-demo/
cd scs-credit-card-demo
```

In separate terminals, go into the directory for the two services and use the bundled Gradle wrapper to start the spring boot application:

Terminal 1:
```
cd MediatorService/
./gradlew bootRun
```

Terminal 2:
```
cd ApiErrorHandlingService/
./gradlew bootRun
```

### Step 2: Trigger the fraudCheck API

To trigger the deployed microservices, issue a HTTP POST operation using a tool like `curl` or [postman](https://www.postman.com/) using the details below. 
(This directly hits the Microgateway feature of the above mentioned public access Event Broker, but could easily have been fronted by an API Gateway product first to pass-thru the HTTP call.)

URI | Username | Password | Content-Type |
---- | ---- | ---- | ---- | 
`https://public-demo-broker.messaging.solace.cloud:9443/fraudCheck` | `scs-demo-public-user` | `scs-demo-public-user` | `application/json` |

Example if using curl:
`curl -u scs-demo-public-user:scs-demo-public-user -H "Content-Type: application/json" -X POST https://public-demo-broker.messaging.solace.cloud:9443/fraudCheck`

If all successful, the empty payload will trigger the Mediator Service to generate an error event message. This will be picked up by the Error Handling Service to construct a suitable JSON response to the caller like so:

```
{
    "status": "error",
    "errorMsg": "Error processing message: Did not receive a valid JSON formatted message. Unexpected token END OF FILE at position 0."
    "elapsedTimeMs": 34,
}
```

No other service (such as the Orchestrator) was needed at this stage. We will go onto to deploy them next.

### Step 3: Start the Orchestrator, Transactions History, Fraud Detection and Card Block Services

Once again across separate terminals, start the services like so:

Terminal 3:
```
cd OrchestratorService/
./gradlew bootRun
```

Terminal 4:
```
cd TransactionsHistoryService/
./gradlew bootRun
```

Terminal 5:
```
cd FraudDetectionService/
./gradlew bootRun
```

Terminal 6:
```
cd CardBlockService/
./gradlew bootRun
```

### Step 4: Invoke fraudCheck API with valid payload

Just as in step 2 above, invoke the API again using your tool of choice. This time with this sample JSON payload:

```
{
	"partner": "onyx",
	"cardNumber": "1234-5678-1234-5688",
	"blockCardIfFraudulent": true
}
```

Using curl this might look something like: 
```
curl -u scs-demo-public-user:scs-demo-public-user -H "Content-Type: application/json" -X POST https://public-demo-broker.messaging.solace.cloud:9443/fraudCheck -d '{ "partner":"onyx", "cardNumber": "1234-5678-1234-5688", "blockCardIfFraudulent":true }'
```

### Step 5: Review Orchestrator log output

The Orchestrator Service is a natural observation point of the whole event flow and processing pipeline. Multiple input channels are used to invoke processing functions to further the orchestrated pipeline.

Review those logs as you submit further requests to observe the interactions.
Randomisation is introduced on the fraud check status to create different responses, as well as for simulating processing delays at each service by sleeping for a number of milliseconds.

A sample log output is below for comparison:
![Sample of Orchestrator Logs](https://github.com/itsJamilAhmed/scs-credit-card-demo/blob/main/images/Sample-Orchestrator-Logs.JPG)

#### Not seeing the logs update? :confused: 
If the logs in your deployed service instance are not updating when you issue the API calls, yet still getting a response, it means another instance deployed by someone else has picked it up and processed it. 

### And that's it!

## Contributing

Welcome any feedback and suggestions as I plan to continue refining this sample.

## Authors

See the list of [contributors](https://github.com/itsJamilAhmed/scs-credit-card-demo/graphs/contributors) who participated in this project 

## License

This project is licensed under the Apache License, Version 2.0. - See the [LICENSE](LICENSE) file for details.

## Resources

For more information try these resources:
- Developer [tutorials/codelabs](https://codelabs.solace.dev/codelabs/solace-workshop-scs/index.html) using the Solace Spring Binder
- List of [configuration parameters](https://github.com/SolaceProducts/solace-spring-cloud/tree/master/solace-spring-cloud-starters/solace-spring-cloud-stream-starter#configuration-options) for the Solace PubSub+ Spring Binder 
- The Solace Spring Cloud Streams Project on [GitHub](https://github.com/SolaceProducts/solace-spring-cloud)
- Get a better understanding of [Solace Event Brokers](https://solace.com/products/event-broker/)
- The Solace [Developer Portal](https://solace.dev)
- Check out the [Solace blog](https://solace.com/blog/) for other interesting discussions around Solace technology
- Ask the [Solace community](https://solace.community/) for help

