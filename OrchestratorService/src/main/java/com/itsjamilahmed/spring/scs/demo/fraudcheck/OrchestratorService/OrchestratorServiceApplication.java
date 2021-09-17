package com.itsjamilahmed.spring.scs.demo.fraudcheck.OrchestratorService;

import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import java.util.UUID;
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
public class OrchestratorServiceApplication {

	private static final Logger log = LoggerFactory.getLogger(OrchestratorServiceApplication.class);
	
	// Which message header key stores the message timestamp and destination?
	static final String SOL_MSG_TIMESTAMP_KEY = "timestamp";
	static final String SOL_DESTINATION_KEY = "solace_destination";
	
	// How to identify the headers on the inbound event that should be copied over to the outbound event?
	static final String APP_HEADERS_KEY_PREFIX = "app_";
	
	// Which header to specify the reply-to topic for the outbound messages?
	static final String REPLY_TO_HEADER_KEY = "reply_to_destination";
	
	static final String SOURCE_PLATFORM_NAME_KEY = "app_sourcePlatform";
	
	// Root topic of various services this orchestrator will leverage
	static final String EVENT_TOPIC_OUT_ROOT_TXN =        "myBank/cards/txnService/history/req/v1";
	static final String EVENT_TOPIC_OUT_ROOT_FRAUDCHECK = "myBank/cards/fraudService/status/req/v1";
	static final String EVENT_TOPIC_OUT_ROOT_CARDBLOCK =  "myBank/cards/cardService/block/req/v1";
	
	// Root of reply-to topics to get various responses back to this service
	static final String REPLY_TO_TOPIC_ROOT_TXN =        "myBank/cards/fraudCheckApi/reply/txnService/history/v1";
	static final String REPLY_TO_TOPIC_ROOT_FRAUDCHECK = "myBank/cards/fraudCheckApi/reply/fraudService/status/v1";
	static final String REPLY_TO_TOPIC_ROOT_CARDBLOCK =  "myBank/cards/fraudCheckApi/reply/cardService/block/v1";
	
	// Any requests that could not get processed properly, send to an error topic to be picked up by a dedicated service. 
	// (e.g. Construct an appropriate error message and send back to the waiting microgateway reply-to and onwards to the API caller.)
	static final String EVENT_TOPIC_OUT_ERROR = "myBank/cards/fraudCheckApi/error";
	
	// Header that signals whether a card block is desired if fraud detected
	// It will be an example of some state carried forward through the event processing pipeline of multiple services
	static final String IS_CARD_BLOCK_REQ_HEADER_KEY = APP_HEADERS_KEY_PREFIX + "fraudCheckOrchestrator_isBlockRequested";
	static final String PARTNER_NAME_HEADER_KEY = APP_HEADERS_KEY_PREFIX + "fraudCheckOrchestrator_partnerName";
	
	// How to get the details for routing back the final API response?
	static final String MEDIATOR_REPLYTO_DESTINATION_KEY = APP_HEADERS_KEY_PREFIX + "fraudCheckMediator_replyTo";
	static final String MEDIATOR_CORRELATION_ID_KEY = APP_HEADERS_KEY_PREFIX + "fraudCheckMediator_correlationId";
	static final String MEDIATOR_MSG_TIMESTAMP_KEY = APP_HEADERS_KEY_PREFIX + "fraudCheckMediator_timestamp";

	static final String SOL_CORRELATION_ID_KEY = "solace_correlationId";
	
	// Purpose: Orchestrate the multiple steps involved to support the external API call of checking whether a card has been used fraudulently
	//  1) Get recent transactions involving the card
	//  2) Send the transactions list to the fraud detection service to analyse
	//  3a) If fraud detected, block the card. (An optional step if the original request specified the card to remain active.)
	//  3b) In parallel send a response back for the original API call of whether fraud detected and if a card block is being processed
	public static void main(String[] args) {
		SpringApplication.run(OrchestratorServiceApplication.class, args);
	}
		
	@Bean
	public Function<Message<String>, Message<String>> getRecentTransactions(){
		return input -> {
			
			String payload = input.getPayload();
			JSONObject jsonMessageIn;
			JSONObject jsonMessageOut;
			log.info("Received fraud check request: " + input.getPayload() + " on topic: " + input.getHeaders().get(SOL_DESTINATION_KEY));
			
			String partnerName = "";    // A potentially routable property to use in the construction of the final output topic
			String sourcePlatformName;	// A potentially routable property to use in the construction of the final output topic
			
			// An element to help uniquely differentiate each outbound request and resulting reply in the topics generated
			String uuid = UUID.randomUUID().toString();
			
			String outputTopic = "";				// Dynamically determined on a per-message basis
			String replyToTopic = "";				// Route responses back to this service (or other instances of it)
			
			boolean isCardBlockRequested = true;	// Default behaviour is to block the card
			
			// Just to facilitate a meaningful log output:
			String outputTypeForLogging = "Sending get-transactions request: ";
			
			try {
				try {
					jsonMessageIn = (JSONObject) new JSONParser().parse(payload);
					
					// Construct a new message for the Transactions Service with only what it needs
					jsonMessageOut = new JSONObject();
					
					// Simply, a request to return the last 5 transactions for the given card number...
					jsonMessageOut.put("cardNumber",   jsonMessageIn.get("cardNumber").toString());
					jsonMessageOut.put("txnCount", 5);
					
					// Other useful 'state' from this request to carry forward in the event processing pipeline?
					// -> There is branching logic on whether to block the card or not, even if fraud detected
					// -> Put that boolean in a message header that will be carried forward in the response too
					isCardBlockRequested = (boolean) jsonMessageIn.get("blockCardIfFraudulent");
					
					// Finally, get elements from the message header for topic building purposes
					// Note: Not strictly necessary for the flow to work, however an example of inserting useful routable
					// elements into the topic to help downstream consumers filter or access-control to very specific interest if needed
					partnerName = jsonMessageIn.get("partner").toString();
					sourcePlatformName = input.getHeaders().get(SOURCE_PLATFORM_NAME_KEY).toString();
					
					// Build the topics
					outputTopic = EVENT_TOPIC_OUT_ROOT_TXN + "/" + sourcePlatformName + "/" + partnerName + "/" + uuid;
					replyToTopic = REPLY_TO_TOPIC_ROOT_TXN + "/" + sourcePlatformName + "/" + partnerName + "/" + uuid;
					
				} catch (ParseException e) {
					log.error("Did not receive a valid JSON formatted message. " + e.toString());
					throw new Exception("Did not receive a valid JSON formatted message. " + e.toString());
					
				} catch (NullPointerException e) {
					// Expected to throw if accessing missing fields in the json message
					log.error("Error processing message: Mandatory fields missing. ");
					throw new Exception("Error processing message: Mandatory fields missing. ");
				}
			} catch (Exception e) {
				jsonMessageOut = new JSONObject();
				jsonMessageOut.put("errorMsg", e.getMessage());
				
				outputTopic = EVENT_TOPIC_OUT_ERROR;
				outputTypeForLogging = "Sending processing-error message: ";
			}
			
			Message<String> output = MessageBuilder.withPayload(jsonMessageOut.toString())
					.setHeader(REPLY_TO_HEADER_KEY, replyToTopic)
					.setHeader(IS_CARD_BLOCK_REQ_HEADER_KEY, isCardBlockRequested)
					.setHeader(PARTNER_NAME_HEADER_KEY, partnerName)
					.setHeader(BinderHeaders.TARGET_DESTINATION, outputTopic)
					.build();
			
			// Copy over all the app headers...
			output = copyAppMessageHeaders(input, output);

			log.info(outputTypeForLogging + output.getPayload() + " on topic: " + outputTopic + " with reply-to topic: " + replyToTopic);
			
			return output;
		};
	}
	
	@Bean
	public Function<Message<String>, Message<String>> getFraudStatus(){
		return input -> {
			
			String payload = input.getPayload();
			JSONObject jsonMessageIn;
			JSONObject jsonMessageOut;
			log.info("Successfully received transactions service response: " + input.getPayload() + " on topic: " + input.getHeaders().get(SOL_DESTINATION_KEY));
		
			String partnerName;		    // A potentially routable property to use in the construction of the final output topic
			String sourcePlatformName;	// A potentially routable property to use in the construction of the final output topic
			
			// An element to help uniquely differentiate each outbound request and resulting reply in the topics generated
			String uuid = UUID.randomUUID().toString();
			
			String outputTopic = "";		// Dynamically determined on a per-message basis
			String replyToTopic = "";		// Route responses back to this service (or other instances of it)
			
			// Just to facilitate meaningful log output:
			String outputTypeForLogging = "Sending get-fraud-status request: ";
			
			try {
				try {
					jsonMessageIn = (JSONObject) new JSONParser().parse(payload);
					
					// Was the response content itself OK?
					if (jsonMessageIn.get("status").toString().equalsIgnoreCase("ok")) {
						// Proceed with the next step of the orchestration:
						
						// Construct a new message for the Fraud Status Check Service with only what it needs
						jsonMessageOut = new JSONObject();
						
						// Simply, a request to analyse the last 5 transactions for the given card number...
						jsonMessageOut.put("cardNumber", jsonMessageIn.get("cardNumber").toString());
						jsonMessageOut.put("recentTxns", (JSONArray) jsonMessageIn.get("txns"));
						
						// Finally, get elements from the message header for topic building purposes
						// Note: Not strictly necessary for the flow to work, however an example of inserting useful routable
						// elements into the topic to help downstream consumers filter or access-control to very specific interest if needed
						partnerName = input.getHeaders().get(PARTNER_NAME_HEADER_KEY).toString();
						sourcePlatformName = input.getHeaders().get(SOURCE_PLATFORM_NAME_KEY).toString();
						
						// Build the topics
						outputTopic = EVENT_TOPIC_OUT_ROOT_FRAUDCHECK + "/" + sourcePlatformName + "/" + partnerName + "/" + uuid;
						replyToTopic = REPLY_TO_TOPIC_ROOT_FRAUDCHECK + "/" + sourcePlatformName + "/" + partnerName + "/" + uuid;
					}
					else
					{
						// TODO: Determine failure handling strategy. 
						// Retry the request? Error out immediately to the API caller? Does the external API (HTTP) Gateway retry?
						// For now, everything immediately passes back to the caller a generic message to try again.						
						log.error("Transactions service response was not OK: " + jsonMessageIn.get("errorMsg"));
						throw new Exception("An internal error occurred. Please retry the operation.");
					}
					
				} catch (ParseException e) {
					log.error("Did not receive a valid JSON formatted message. " + e.toString());
					throw new Exception("An internal error occurred. Please retry the operation.");
					
				} catch (NullPointerException e) {
					// Expected to throw if accessing missing fields in the json message
					log.error("Error processing message: NullPointerException during json access. ");
					e.printStackTrace();
					throw new Exception("An internal error occurred. Please retry the operation.");
				}
			} catch (Exception e) {		
				jsonMessageOut = new JSONObject();
				jsonMessageOut.put("errorMsg", e.getMessage());
				
				outputTopic = EVENT_TOPIC_OUT_ERROR;
				outputTypeForLogging = "Sending processing-error message: ";
			}
			
			Message<String> output = MessageBuilder.withPayload(jsonMessageOut.toString())
					.setHeader(REPLY_TO_HEADER_KEY, replyToTopic)
					.setHeader(BinderHeaders.TARGET_DESTINATION, outputTopic)
					.build();
			
			// Copy over all the app headers...
			output = copyAppMessageHeaders(input, output);

			log.info(outputTypeForLogging + output.getPayload() + " on topic: " + outputTopic + " with reply-to topic: " + replyToTopic);
			
			return output;
		};
	}
	
	@Bean
	public Function<Message<String>, Message<String>> requestCardBlock(){
		return input -> {
			
			String payload = input.getPayload();
			JSONObject jsonMessageIn;
			JSONObject jsonMessageOut;
			log.info("Successfully received fraud status response: " + input.getPayload() + " on topic: " + input.getHeaders().get(SOL_DESTINATION_KEY));
		
			String partnerName;		    // A potentially routable property to use in the construction of the final output topic
			String sourcePlatformName;	// A potentially routable property to use in the construction of the final output topic
			
			// An element to help uniquely differentiate each outbound request and resulting reply in the topics generated
			String uuid = UUID.randomUUID().toString();
			
			String outputTopic = "";		// Dynamically determined on a per-message basis
			String replyToTopic = "";		// Route responses back to this service (or other instances of it)
			
			// Just to facilitate helpful log output:
			String outputTypeForLogging = "Sending card block request (for deferred execution): ";
			jsonMessageOut = new JSONObject();
			
			try {
				try {
					jsonMessageIn = (JSONObject) new JSONParser().parse(payload);
					
					// Was the response content itself OK?
					if (jsonMessageIn.get("status").toString().equalsIgnoreCase("ok")) {
						
						// Proceed with the next step of the orchestration:
						
						// Was fraud detected and a card block requested if so?
						
						boolean fraudDetected = (boolean) jsonMessageIn.get("fraudDetected");
						boolean blockRequested = (boolean) input.getHeaders().get(IS_CARD_BLOCK_REQ_HEADER_KEY);
						
						if (fraudDetected) {
							
							if (blockRequested) {
								log.info("Fraud was detected and a card block was requested too.");
								
								// Construct a new message for the Card Block Service with only what it needs
								
								// Simply, a request to block the given card number...
								jsonMessageOut.put("cardNumber", jsonMessageIn.get("cardNumber").toString());
								jsonMessageOut.put("setCardBlockStatus", true);
								
								// Finally, get elements from the message header for topic building purposes
								// Note: Not strictly necessary for the flow to work, however an example of inserting useful routable
								// elements into the topic to help downstream consumers filter or access-control to very specific interest if needed
								partnerName = input.getHeaders().get(PARTNER_NAME_HEADER_KEY).toString();
								sourcePlatformName = input.getHeaders().get(SOURCE_PLATFORM_NAME_KEY).toString();
								
								// Build the topics
								outputTopic = EVENT_TOPIC_OUT_ROOT_CARDBLOCK + "/" + sourcePlatformName + "/" + partnerName + "/" + uuid;
								replyToTopic = REPLY_TO_TOPIC_ROOT_CARDBLOCK + "/" + sourcePlatformName + "/" + partnerName + "/" + uuid;
							} else {
								log.info("No further outbound event for card block service required. (Fraud was detected but card block not desired.)");
							}							
						}
						else {
							// Nothing required to do...
							log.info("No further outbound event for card block service required. (No fraud was detected.)");
						}
						
					}
					else
					{
						// TODO: Determine failure handling strategy. Retry the request? error out immediately to the API caller? etc
						// 		 For now, everything immediately passes back to the caller a generic message to try again						
						log.error("Fraud Detection service response was not OK: " + jsonMessageIn.get("errorMsg"));
						throw new Exception("An internal error occurred. Please retry the operation.");
					}
					
				} catch (ParseException e) {
					log.error("Did not receive a valid JSON formatted message. " + e.toString());
					throw new Exception("An internal error occurred. Please retry the operation.");
				} catch (NullPointerException e) {
					// Expected to throw if accessing missing fields in the json message
					log.error("Error processing message: NullPointerException during json access. ");
					e.printStackTrace();
					throw new Exception("An internal error occurred. Please retry the operation.");
				}
			} catch (Exception e) {
				
				jsonMessageOut = new JSONObject();
				jsonMessageOut.put("errorMsg", e.getMessage());
				
				outputTopic = EVENT_TOPIC_OUT_ERROR;
				outputTypeForLogging = "Sending processing-error message: ";
			}
			
			if (jsonMessageOut.isEmpty()) {
				return null;
			} else {
				Message<String> output = MessageBuilder.withPayload(jsonMessageOut.toString())
						.setHeader(REPLY_TO_HEADER_KEY, replyToTopic)
						.setHeader(BinderHeaders.TARGET_DESTINATION, outputTopic)
						.build();
				
				// Copy over all the app headers...
				output = copyAppMessageHeaders(input, output);

				log.info(outputTypeForLogging + output.getPayload() + " on topic: " + outputTopic + " with reply-to topic: " + replyToTopic);
				
				return output;
			}

		};
	}
		
	@Bean
	public Function<Message<String>, Message<String>> returnFinalResponse(){
		return input -> {
			
			String payload = input.getPayload();
			JSONObject jsonMessageIn;
			JSONObject jsonMessageOut;
			log.info("Successfully received fraud status to create final response: " + input.getPayload() + " on topic: " + input.getHeaders().get(SOL_DESTINATION_KEY));
			
			String outputTopic = "";		// Dynamically determined on a per-message basis
			
			// Just to facilitate helpful log output:
			String outputTypeForLogging = "Sending final API response: ";
			jsonMessageOut = new JSONObject();
			
			try {
				try {
					jsonMessageIn = (JSONObject) new JSONParser().parse(payload);
					
					// Was the response content itself OK?
					if (jsonMessageIn.get("status").toString().equalsIgnoreCase("ok")) {
						
						// Proceed with the final message creation:
						jsonMessageOut.put("status", "ok");
						jsonMessageOut.put("cardNumber", jsonMessageIn.get("cardNumber"));
						
						boolean fraudDetected = (boolean) jsonMessageIn.get("fraudDetected");
						boolean blockRequested = (boolean) input.getHeaders().get(IS_CARD_BLOCK_REQ_HEADER_KEY);
						
						jsonMessageOut.put("fraudDetected", fraudDetected);
						if (fraudDetected) {
							// Is the card being blocked?
							jsonMessageOut.put("cardBlockRequested", blockRequested);
						}
						
						// May as well calculate the elapsed time between receiving the original request and this error event
						long finalMessageTimestampMs = Long.parseLong(input.getHeaders().getOrDefault(SOL_MSG_TIMESTAMP_KEY, "0").toString());
						long originalRequestTimestampMs = Long.parseLong(input.getHeaders().getOrDefault(MEDIATOR_MSG_TIMESTAMP_KEY, "0").toString());
						
						jsonMessageOut.put("elapsedTimeMs", finalMessageTimestampMs - originalRequestTimestampMs); 
						

						// Build the topics
						outputTopic = input.getHeaders().get(MEDIATOR_REPLYTO_DESTINATION_KEY).toString();
					}
					else
					{
						// TODO: Determine failure handling strategy. Retry the request? error out immediately to the API caller? etc
						// 		 For now, everything immediately passes back to the caller a generic message to try again						
						log.error("Fraud Check service response was not OK: " + jsonMessageIn.get("errorMsg"));
						throw new Exception("An internal error occurred. Please retry the operation.");
					}
					
				} catch (ParseException e) {
					log.error("Did not receive a valid JSON formatted message. " + e.toString());
					throw new Exception("An internal error occurred. Please retry the operation.");
				} catch (NullPointerException e) {
					// Expected to throw if accessing missing fields in the json message
					log.error("Error processing message: NullPointerException during json access. ");
					e.printStackTrace();
					throw new Exception("An internal error occurred. Please retry the operation.");
				}
			} catch (Exception e) {
				
				jsonMessageOut = new JSONObject();
				jsonMessageOut.put("errorMsg", e.getMessage());
				
				outputTopic = EVENT_TOPIC_OUT_ERROR;
				outputTypeForLogging = "Sending processing-error message: ";
			}
			
			Message<String> output = MessageBuilder.withPayload(jsonMessageOut.toString())
					.setHeader(SOL_CORRELATION_ID_KEY, input.getHeaders().getOrDefault(MEDIATOR_CORRELATION_ID_KEY, ""))
					.setHeader(BinderHeaders.TARGET_DESTINATION, outputTopic)
					.build();

			log.info(outputTypeForLogging + output.getPayload() + " on topic: " + outputTopic);
			
			return output;
		};
	}
	
	private Message<String> copyAppMessageHeaders (Message<String> fromMsg, Message<String> toMsg) {
		
		// Now copy over all the app headers...
		for (String key : fromMsg.getHeaders().keySet()) {
			if (key.startsWith(APP_HEADERS_KEY_PREFIX)) {
				
				// Message is immutable so need to create new for each header?
				// TODO: Investigate some optimisation
				toMsg = MessageBuilder.fromMessage(toMsg)
						.setHeader(key, fromMsg.getHeaders().get(key))
						.build();
			}
		}
		
		return toMsg; 
	}
	
}
