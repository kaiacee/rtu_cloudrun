package com.deepdyve.rtu_cloudrun;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.Nats;
import io.nats.client.api.PublishAck;

import java.io.IOException;

import static com.deepdyve.rtu_cloudrun.Constants.mqUrl;

public class MQProcessor {
    static Connection nc;
    JetStream jetStream;

    public Connection getNatsConnection() { return nc; }

    // entry if already validated nc
    public MQProcessor(Connection nc) throws IOException {
        this.nc = nc;
        jetStream = nc.jetStream();
    }

    // normal entrypoint
    public MQProcessor() throws IOException, InterruptedException, JetStreamApiException {
        nc = Nats.connect(mqUrl); // 20241025 - for some reason its getting tons of warning meessages; use above to try and limit
        jetStream = nc.jetStream();
        if (jetStream == null) {
            throw new RuntimeException("Nats connection failed");
        }
    }

    /* publishes raw message on subjectOut */
    public boolean sendMessage(String subjectOut, String data) {
        return sendMessage(jetStream, subjectOut, data);
    }
    public static boolean sendMessage(JetStream jetStream, String subjectOut, String data) {
        int retries = 5;
        int delay = 100; // Start with 100ms
        do {
            try {
                PublishAck ack = jetStream.publish(subjectOut, data.getBytes());
                // Ensure the message was successfully stored
                if (ack == null || ack.getSeqno() <= 0) {
                    System.err.println("WARNING: No valid ACK received for subject: " + subjectOut);
                    try {
                        Thread.sleep(delay); // Wait before retrying
                        delay *= 2; // Exponential backoff
                    } catch (InterruptedException ignored) {}
                } else {
                    return true; // Successfully published
                }
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    Thread.sleep(delay); // Wait before retrying
                    delay *= 2; // Exponential backoff
                } catch (InterruptedException ignored) {}
            }
        } while (retries-- > 0);
        System.err.println("ERROR sending message " + subjectOut + "\t" + data + " - retries failed !");
        return false;
    }

    public void close() {
        if (nc != null) {
            try {
                nc.close();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
