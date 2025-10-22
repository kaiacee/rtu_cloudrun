package com.deepdyve.rtu_cloudrun;

import com.google.cloud.functions.CloudEventsFunction;
import io.cloudevents.CloudEvent;

public class RunOnUpload implements CloudEventsFunction {
    private static MQProcessor mqProcessor;

        /*
        When your container starts, initializes the application once,
        and then keeps that container instance alive as a request handler.
        After startup, Cloud Run keeps the process idle until it receives a request/event.
        Cloud Run then holds the instance “warm” waiting to receive HTTP requests or Eventarc CloudEvent POSTs.
         */

    static {
        try {
            Constants.init();
            mqProcessor = new MQProcessor();
        } catch (Exception e) {
            throw new RuntimeException(e);
       }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown hook triggered: Closing NATS connection...");
            if (mqProcessor != null) {
                mqProcessor.close();
            }
            System.out.println("NATS connection closed.");
        }));
    }

    /**
     * event  handler
     * @param event
     * @throws Exception
     */
    @Override
    public void accept(CloudEvent event) throws Exception {
        System.out.println("Received event " + event.getId()  + "  Source: " + event.getSource());
        String eventType = event.getType();
        switch(eventType) {
            case "google.cloud.storage.object.v1.finalized":
                System.out.println("Finalized event");
                break;
            default:
                System.out.println("Received unknown event type: " + eventType);
        }
    }
}
