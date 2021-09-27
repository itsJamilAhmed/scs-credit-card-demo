# Spring Cloud Stream Demo: Credit Card Fraud Checking

## Table of Contents :bookmark_tabs:  
* [Repository Purpose](#repository-purpose)
  * [Implementing request reply with Spring Cloud Stream](#implementing-request-reply-with-spring-cloud-stream)
  * [Use-Case Information: Credit Card Fraud Check](#use-case-information-credit-card-fraud-check)
* [Services in this repository](#services-in-this-repository)
  * [Implementation Principles](#implementation-principles)
* [Running the demo services](#running-the-demo-services)
  * [Pre-requisites](#pre-requisites-white_check_mark)
  * [Step by step instructions](#step-one-start-the-mediator-and-error-handling-service)
* [Appendix: Topic Taxonomy](#appendix-topic-taxonomy) 
* [Contributing](#contributing)
* [Authors](#authors)
* [License](#license)
* [Resources](#resources)

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

## Running the demo services

### Pre-requisites :white_check_mark:

#### Option A: Use the public access Event Broker

The configuration files (`application.yaml`) for each of the services uses a PubSub+ Event Broker available for public access and hosted in [Solace Cloud](https://solace.com/products/event-broker/cloud/). This means you are able to checkout the project and run it without needing to [setup an Event Broker of your own first](https://www.solace.dev/). Your running instances of the service will automatically join the competitive consumption against any other instance already running by someone else.

#### Option B: Use your own Event Broker 

If you want to instead leverage your own broker instance: 
1. Update the `spring > cloud > stream > binders > solace-broker` section of each `application.yaml` configuration file first with connection details of your message VPN.
2. Configure the microgateway feature to be in `gateway` mode for the message VPN. (More details [here](https://docs.solace.com/Configuring-and-Managing/Microgateway-Tasks/Managing-Microgateway.htm#Configure_VPN_Mode).)
3. If your client-username does not have permission to provision durable endpoints through the API, create the [necessary queues](https://github.com/itsJamilAhmed/scs-credit-card-demo/blob/main/images/fraudCheck-Queues-List.jpg) with the topic subscriptions as present in the `application.yaml` file. 
4. Use the REST hostname and port for your message VPN in the commands below that represent the external API caller. (i.e. the `curl` or [postman](https://www.postman.com/) steps.)

### Step :one:: Start the Mediator and Error Handling Service

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

### Step :two:: Trigger the fraudCheck API

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

### Step :three:: Start the Orchestrator, Transactions History, Fraud Detection and Card Block Services

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

### Step :four:: Invoke fraudCheck API with valid payload

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

### Step :five:: Review Orchestrator log output

The Orchestrator Service is a natural observation point of the whole event flow and processing pipeline. Multiple input channels are used to invoke processing functions to further the orchestrated pipeline.

Review those logs as you submit further requests to observe the interactions.
Randomisation is introduced on the fraud check status to create different responses, as well as for simulating processing delays at each service by sleeping for a number of milliseconds.

A sample log output is below for comparison:
![Sample of Orchestrator Logs](https://github.com/itsJamilAhmed/scs-credit-card-demo/blob/main/images/Sample-Orchestrator-Logs.JPG)

#### Not seeing the logs update? :confused: 
If the logs in your deployed service instance are not updating when you issue the API calls, yet still getting a response, it means another instance deployed by someone else has picked it up and processed it. 

### Step :six:: Terminate the Orchestrator Service

This last step is about demonstrating another exception path in the processing pipeline. 
Assuming your instance of the Orchestrator was the only one running, or you are running these tests against your own dedicated Event Broker, the lack of the Orchestrator Service effectively means the API cannot be properly serviced. After the request has passed the mediator service, what options are available to monitor the pipeline and take any recovery steps?

While the powers of persistent messaging mean that request message can sit forever waiting for the Orchestrator Service to return, it is not the desired behaviour with a synchronous call essentially waiting for a response. That call actually needs to timeout gracefully in this exceptional scenario so the caller can try again later. 
Fortunately, the Solace PubSub+ features of message [time-to-live and dead-message-queues (DMQs)](https://docs.solace.com/Solace-JMS-API/Setting-Message-Properties.htm?Highlight=Time%20to%20live) can help here. 

The Mediator Service can set some message properties to make that request time out after a given period (say 3 seconds), and let that message move to the inbound channel (i.e. queue) of another waiting service instead. That is the Error Handling Service in this example, with that assuming responsibility to send an apprioriate response to the caller.

With the Orchestrator Service terminated, issue a new API call like in Step 4 earlier.

Using curl this might look something like: 
```
curl -u scs-demo-public-user:scs-demo-public-user -H "Content-Type: application/json" -X POST https://public-demo-broker.messaging.solace.cloud:9443/fraudCheck -d '{ "partner":"onyx", "cardNumber": "1234-5678-1234-5688", "blockCardIfFraudulent":true }'
```

After a 3 second wait, you should see a response like so:
```
{
    "status": "error",
    "errorMsg": "This service is currently unavailable. Please try again later.",
    "elapsedTimeMs": 3090
}
```

### And that's it!

## Appendix: Topic Taxonomy

These sample services use a topic taxonomy to demonstrate three important concepts:
1. Wildcarded subscription by consumers to attract events of interest
2. Dynamic elements being incorporated into a final target destination as set by publishers
3. Topic destinations being generated within a specific 'reply' range in the taxonomy to direct responses back to a given service. (i.e. the Orchestrator)

For advice on defining a comprehensive topic taxonomy, consult the [Solace documentation here](https://docs.solace.com/Best-Practices/Topic-Architecture-Best-Practices.htm). The topics used by these services favour brevity and may not incorporate all the necessary best practices. 

In the table below, elements in `{}` are dynamic elements as determined from either the original API request contents (e.g. `{partner}`) or static strings defined in the service to identify itself as a source system (e.g. `{platform}`).

Service | Subscribe Topic | Publish Topic | Error Topic |
---- | -------- | ------ | ---- |
fraudCheck Mediator | `POST/fraudCheck` | `myBank/cards/fraudCheckApi/status/v1/{platform}/{partner}` | `myBank/cards/fraudCheckApi/error` |
fraudCheck Error Handling | `myBank/cards/fraudCheckApi/error` | Topic string as provided in message header `app_fraudCheckMediator_replyTo` | N/A |
fraudCheck **Orchestrator** (getRecentTransactions) | `myBank/cards/fraudCheckApi/status/v1/>` | `myBank/cards/txnService/history/req/v1/{platform}/{partner}/{UUID}` | `myBank/cards/fraudCheckApi/error` |
fraudCheck **Orchestrator** (getFraudStatus) | `myBank/cards/fraudCheckApi/reply/txnService/history/v1/>` | `myBank/cards/fraudService/status/req/v1/{platform}/{partner}/{UUID}` | `myBank/cards/fraudCheckApi/error` |
fraudCheck **Orchestrator** (requestCardBlock) | `myBank/cards/fraudCheckApi/reply/fraudService/status/v1/>` | `myBank/cards/cardService/block/req/v1/{platform}/{partner}/{UUID}` | `myBank/cards/fraudCheckApi/error` |
fraudCheck **Orchestrator** (returnFinalResponse) | `myBank/cards/fraudCheckApi/reply/fraudService/status/v1/>` | Topic string as provided in message header `app_fraudCheckMediator_replyTo` | `myBank/cards/fraudCheckApi/error` |
Transactions History | `myBank/cards/txnService/history/req/v1/>` | Topic string as provided in message header `reply_to_destination` | N/A |
Fraud Detection | `myBank/cards/fraudService/status/req/v1/>` | Topic string as provided in message header `reply_to_destination` | N/A |
Card Block | `myBank/cards/cardService/block/req/v1/>` | Topic string as provided in message header `reply_to_destination` | N/A |

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

