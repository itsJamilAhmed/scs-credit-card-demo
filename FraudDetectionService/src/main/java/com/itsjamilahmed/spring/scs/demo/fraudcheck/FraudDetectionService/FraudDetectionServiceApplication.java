package com.itsjamilahmed.spring.scs.demo.fraudcheck.FraudDetectionService;

import java.util.function.Function;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.apache.commons.lang3.RandomUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

@SpringBootApplication
public class FraudDetectionServiceApplication {

	private static final Logger log = LoggerFactory.getLogger(FraudDetectionServiceApplication.class);

	// Which header to specify the reply-to topic for the outbound messages?
	static final String REPLY_TO_HEADER_KEY = "reply_to_destination";
	
	// Just for logging purposes:
	static final String SOL_DESTINATION_KEY = "solace_destination";
	
	// How to identify the headers on the inbound event that should be copied over to the outbound event?
	static final String APP_HEADERS_KEY_PREFIX = "app_";
	
	public static void main(String[] args) {
		SpringApplication.run(FraudDetectionServiceApplication.class, args);
	}
	
	@Bean
	// Purpose: A simple lookup service to return the specified number of recent transactions for a given card number
	//	* For these internally accessed services, error messages are sent back on the same supplied reply-to
	//  * It is the responsibility of the requesting service to detect and implement any logic such as retry pattern
	public Function<Message<String>, Message<String>> getFraudStatus(){
		return input -> {
			
			String payload = input.getPayload();
			JSONObject jsonMessageIn;
			JSONObject jsonMessageOut;
			log.info("Received message: " + input.getPayload() + " on topic: " + input.getHeaders().get(SOL_DESTINATION_KEY));
			
			
			try {
				try {
					jsonMessageIn = (JSONObject) new JSONParser().parse(payload);
					
					// Do some processing now to get the fraud status of the given card and transactions record
					String cardNumber = jsonMessageIn.get("cardNumber").toString();
					JSONArray txnsAll = (JSONArray) jsonMessageIn.get("txns");
					
					simulateProcessingDelay();
					
					// Create a new message to hold the status and send out
					jsonMessageOut = new JSONObject();
					jsonMessageOut.put("cardNumber", jsonMessageIn.get("cardNumber"));
					jsonMessageOut.put("fraudDetected", RandomUtils.nextBoolean());
					
					jsonMessageOut.put("status", "ok");
					
				} catch (ParseException e) {
					log.error("Did not receive a valid JSON formatted message. " + e.toString());
					throw new Exception ("Did not receive a valid JSON formatted message. " + e.toString());
				} catch (NullPointerException e) {
					log.error("Error processing message: NullPointerException during json access. ");
					e.printStackTrace();
					throw new Exception("Error processing message: NullPointerException during json access.");
				}	
			} catch (Exception e) {
				jsonMessageOut = new JSONObject();
				jsonMessageOut.put("status", "error");
				jsonMessageOut.put("errorMsg", "Error processing message: " + e.getMessage());
			}
			
			String replyTopic = input.getHeaders().get(REPLY_TO_HEADER_KEY).toString();
			
			Message<String> output = MessageBuilder.withPayload(jsonMessageOut.toString())
					.setHeader(BinderHeaders.TARGET_DESTINATION, replyTopic)
					.build();
			
			// Copy over all the app headers...
			output = copyAppMessageHeaders(input, output);

			log.info("Sending response: " + output.getPayload() + " on topic: " + replyTopic);
			
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
	
	private void simulateProcessingDelay () {
		
		long leftLimit = 1L;
	    long rightLimit = 1000L;
	    long generatedRandomLong = RandomUtils.nextLong(leftLimit, rightLimit);
	    log.info("Sleeping for " + generatedRandomLong + "ms to simulate processing delay.");
		try {
			Thread.sleep(generatedRandomLong);
		} catch (InterruptedException e) {

		}	
	}

}
