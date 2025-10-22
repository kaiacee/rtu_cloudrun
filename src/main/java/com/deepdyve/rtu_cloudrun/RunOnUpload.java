package com.deepdyve.rtu_cloudrun;

import com.google.cloud.functions.CloudEventsFunction;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.cloudevents.CloudEvent;

import static com.deepdyve.rtu_cloudrun.Constants.mqSubjectOut;

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
        // event.getSource() is  //storage.googleapis.com/projects/_/buckets/rt-upload-staging_qa
        // event.getId() is a UUID
        //System.out.println("Received event " + event.getId()  );
        String name = null, bucket = null, contentType = null, created = null, size = null;
        try {
            String j = new String(event.getData().toBytes());
            //System.out.println("Received data: " + j);
            Gson gson = new Gson();
            JsonObject jobj = gson.fromJson(j, JsonObject.class);
            // significant fields: name, bucket, conetntType, timeCreated, updated // other fields: size, md5Hash ...
            name = jobj.get("name").getAsString();
            bucket = jobj.get("bucket").getAsString();
            contentType = jobj.get("contentType").getAsString();
            created = jobj.get("timeCreated").getAsString();
            size = jobj.get("size").getAsString();
        } catch (Exception e) {}
        String eventType = event.getType();
        switch(eventType) {
            case "google.cloud.storage.object.v1.finalized":
                if (name != null) {
                    System.out.println("Received finalize event for: " + name + " type: " + contentType + " size: " + size + ".  Sending to " + mqSubjectOut);
                    mqProcessor.sendMessage(mqSubjectOut, name);
                } else {
                    try {
                    System.err.println("Could not retrieve name from " + new String(event.getData().toBytes()));
                    } catch (Exception e) {
                    }
                }
                break;
            default:
                System.out.println("Received unknown event type: " + eventType);
        }
    }
}
