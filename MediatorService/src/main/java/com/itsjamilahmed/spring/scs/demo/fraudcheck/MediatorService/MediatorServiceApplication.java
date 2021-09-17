package com.itsjamilahmed.spring.scs.demo.fraudcheck.MediatorService;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.function.Function;

@SpringBootApplication
public class MediatorServiceApplication {

	private static final Logger log = LoggerFactory.getLogger(MediatorServiceApplication.class);
	
	// Which message header keys store the PubSub+ broker inserted reply-to topic and correlation-IDs?
	static final String SOL_REPLYTO_DESTINATION_KEY = "solace_replyTo";
	static final String SOL_CORRELATION_ID_KEY = "solace_correlationId";
	static final String SOL_DESTINATION_KEY = "solace_destination";
	
	// Which message header key stores the message timestamp?
	static final String SOL_MSG_TIMESTAMP_KEY = "timestamp";
	
	// How to identify the headers on the inbound event that should be copied over to the outbound event?
	static final String APP_HEADERS_KEY_PREFIX = "app_";
	
	// Header keys to be used by the mediator service to store the values for pass-thru purposes to other services involved in the processing pipeline
	static final String MEDIATOR_REPLYTO_DESTINATION_KEY = APP_HEADERS_KEY_PREFIX + "fraudCheckMediator_replyTo";
	static final String MEDIATOR_CORRELATION_ID_KEY = APP_HEADERS_KEY_PREFIX + "fraudCheckMediator_correlationId";
	static final String MEDIATOR_MSG_TIMESTAMP_KEY = APP_HEADERS_KEY_PREFIX + "fraudCheckMediator_timestamp";
	
	// What platform is this mediation service and external API supporting?
	static final String SOURCE_PLATFORM_NAME_KEY = APP_HEADERS_KEY_PREFIX + "sourcePlatform";
	static final String SOURCE_PLATFORM_NAME = "ext/zeus";
	
	// As per the internal topic taxonomy, where should the newly created event representing this API operation go?
	// There will be a topic 'root' that is static, then some properties at the end to identify the event more specifically
	// In a real application, these can be configuration properties managed externally to the code
	static final String EVENT_TOPIC_OUT_ROOT = "myBank/cards/fraudCheckApi/status";
	static final String EVENT_TOPIC_OUT_VERSION = "v1";
	
	
	// Any requests that could not get processed properly, send to an error topic to be picked up by a dedicated service. 
	// (e.g. Construct an appropriate error message and send back to the waiting microgateway reply-to and onwards to the API caller.)
	static final String EVENT_TOPIC_OUT_ERROR = "myBank/cards/fraudCheckApi/error";
	
	
	public static void main(String[] args) {
		SpringApplication.run(MediatorServiceApplication.class, args);
	}

	@Bean
	// Purpose: Mediate the translation from the externally facing HTTP API to the internal event-driven architecture and topic taxonomy
	//  * This service will receive request messages to represent the HTTP operation that took place at the external API.
	//  * The PubSub+ Microgateway feature creates that message and asynchronously expects a response message to the embedded reply-to topic
	//  * This service will simply grab that reply-to topic and put it in a header of a new event message to be processed by the event-driven services
	//  * An event can eventually be generated to that reply-to topic for the waiting Microgateway by another microservice in due course
	public Function<Message<String>, Message<String>> mediate(){
		return input -> {
			
			String payload = input.getPayload();
			JSONObject jsonMessage;
			log.info("Received message: " + input.getPayload() + " on topic: " + input.getHeaders().get(SOL_DESTINATION_KEY));
			
			// Expecting valid json so that payload elements can be used to construct to outbound topic destination
			
			// TODO: Full JSON Schema validation of the message so any malformed messages proceed no further to other services
			// For now just check for some field names...
			String[] expectedJsonFields = {"partner", "cardNumber", "blockCardIfFraudulent"};
			
			String partnerName;	// To use in the construction of the final output topic
			String outputTopic;		// Dynamically determined on a per-message basis
			try {
				try {
					jsonMessage = (JSONObject) new JSONParser().parse(payload);
					
					// Message validation
					for (String field : expectedJsonFields) {
						if (!jsonMessage.containsKey(field))
						{
							throw new Exception("Mandatory field missing: " + field);
						}
					}
					
					partnerName = jsonMessage.get("partner").toString();
					outputTopic = EVENT_TOPIC_OUT_ROOT + "/" + EVENT_TOPIC_OUT_VERSION + "/" + SOURCE_PLATFORM_NAME + "/" + partnerName;
					
				} catch (ParseException e) {
					log.error("Did not receive a valid JSON formatted message. " + e.toString());
					throw new Exception ("Did not receive a valid JSON formatted message. " + e.toString());
					
				} 
			} catch (Exception e) {
				log.error("Error processing message: " + e.getMessage());
				
				jsonMessage = new JSONObject();
				jsonMessage.put("status", "error");
				jsonMessage.put("errorMsg", "Error processing message: " + e.getMessage());
				outputTopic = EVENT_TOPIC_OUT_ERROR;
			}
			
			Message<String> output = MessageBuilder.withPayload(jsonMessage.toString())
					.setHeader(MEDIATOR_CORRELATION_ID_KEY,      input.getHeaders().getOrDefault(SOL_CORRELATION_ID_KEY, ""))
					.setHeader(MEDIATOR_REPLYTO_DESTINATION_KEY, input.getHeaders().getOrDefault(SOL_REPLYTO_DESTINATION_KEY, ""))
					.setHeader(MEDIATOR_MSG_TIMESTAMP_KEY,       input.getHeaders().getOrDefault(SOL_MSG_TIMESTAMP_KEY, ""))
					.setHeader(SOURCE_PLATFORM_NAME_KEY,         SOURCE_PLATFORM_NAME)
					.setHeader(BinderHeaders.TARGET_DESTINATION, outputTopic)
					.build();
			
			return output;
		};
	}
}
