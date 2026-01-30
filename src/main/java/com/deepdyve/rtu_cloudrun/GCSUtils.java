package com.deepdyve.rtu_cloudrun;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.*;

import java.io.FileInputStream;
import java.io.IOException;

import static com.deepdyve.rtu_cloudrun.Constants.gcpCredentialsFile;
import static com.deepdyve.rtu_cloudrun.Constants.gcpProject;

public class GCSUtils {
    public static StorageOptions gcsStorageOptions = null;

    public static void gcsMove(String fromBucket, String fromPath, String toBucket, String toPath) throws ClassNotFoundException, IOException {
        if (gcsStorageOptions == null) {
            Class.forName("com.deepdyve.rtu_cloudrun.Constants"); //calling this to start the static block which populates fields
            gcsStorageOptions = StorageOptions.newBuilder()
                    .setProjectId(gcpProject)
                    .setCredentials(GoogleCredentials.fromStream(new
                            FileInputStream(gcpCredentialsFile))).build();
        }
        Storage storage = gcsStorageOptions.getService();

        try {
            BlobId source = BlobId.of(fromBucket, fromPath);
            BlobId target = BlobId.of(toBucket, toPath);

            BlobInfo targetInfo = BlobInfo.newBuilder(target).build();

            Storage.CopyRequest req = Storage.CopyRequest.newBuilder()
                    .setSource(source)
                    .setTarget(targetInfo)
                    .build();

            Blob b = storage.copy(req).getResult(); // server-side copy
            if (b != null) {
                storage.delete(source);
                //System.out.println("Deleting  " + fromBucket + "/" + fromPath );
            } else {
                System.out.println("UNABLE TO DELETE  " + fromBucket + "/" + fromPath );
            }
        } catch (Exception ex) {
            System.err.println("GCS Move: " + ex.getMessage());
        }
    }

}
