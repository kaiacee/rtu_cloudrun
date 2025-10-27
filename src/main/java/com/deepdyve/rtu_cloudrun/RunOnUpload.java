package com.deepdyve.rtu_cloudrun;

import com.google.cloud.Timestamp;
import com.google.cloud.datastore.*;
import com.google.cloud.functions.CloudEventsFunction;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.cloudevents.CloudEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static com.deepdyve.rtu_cloudrun.Constants.mqSubjectOut;

public class RunOnUpload implements CloudEventsFunction {
    private static MQProcessor mqProcessor;
    private static Datastore ds = DatastoreOptions.getDefaultInstance().getService(); // uses ADC on Cloud Run

        /*
        When your container starts, initializes the application once,
        and then keeps that container instance alive as a request handler.
        After startup, Cloud Run keeps the process idle until it receives a request/event.
        Cloud Run then holds the instance “warm” waiting to receive HTTP requests or Eventarc CloudEvent POSTs.
         */
    private static List<String> watches;
    private static HashMap<String, Dependencies> watchesWithDependencies;

    static {
        try {
            Constants.init();
            mqProcessor = new MQProcessor();
        } catch (Exception e) {
            throw new RuntimeException(e);
       }
        watches = new ArrayList<>();
        watches.add("aea");
        watches.add("ers");
        watches.add("allen_press");
        watches.add("ams");
        watches.add("aps");
        watches.add("bmj");
        watches.add("brill");
        watches.add("bioscientifica");
        watches.add("cabi");
        watches.add("acm");
        watches.add("csiro");
        watches.add("cup");
        watches.add("cu_press");
        watches.add("degruyter");
        watches.add("emerald");
        watches.add("emerald_books");
        watches.add("guilford");
        watches.add("inderscience");
        watches.add("informs");
        watches.add("iop");
        watches.add("karger");
        watches.add("kli");
        watches.add("mag");
        watches.add("mit_press");
        watches.add("eupress");
        watches.add("nejm");
        watches.add("ou_press");
        watches.add("pmc");
        watches.add("pubmed_ftp");
        watches.add("rsc");
        watches.add("rsna");
        watches.add("sage");
        watches.add("spandidos_pub");
        watches.add("spie");
        watches.add("springer_journal");
        watches.add("sj_ebooks");
        watches.add("taylor_francis");
        watches.add("uchi_press");
        watches.add("utp");
        watches.add("wiley");
        watches.add("wiley_books");
        watches.add("wolters_kluwer");
        watches.add("uc_press");
        watches.add("wspc");
        watchesWithDependencies = new HashMap<>();
        watchesWithDependencies.put("jama", new Dependencies('_',"_xml.zip", "_xml.zip", "_pdf.zip"));
        watchesWithDependencies.put("iospress", new Dependencies('.', ".xml", ".xml", ".pdf"));
        watchesWithDependencies.put("imanager",  new Dependencies('.', ".txt",".txt", ".pdf"));

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
        String name = null, contentType = null, size = null;
        String datasourcekeyname = null;
        try {
            String j = new String(event.getData().toBytes());
            //TESTING: System.out.println("Received data: " + j);
            Gson gson = new Gson();
            JsonObject jobj = gson.fromJson(j, JsonObject.class);
            name = jobj.get("name").getAsString();  // format of:
            datasourcekeyname = name.replaceAll("/.*", ""); // usually datasource but sometimes _books or _ftp ....
            if (!watches.stream().anyMatch(datasourcekeyname::equals) &&
                    !watchesWithDependencies.containsKey(datasourcekeyname)) {
                System.out.println("Ignoring non-watched filename: " + datasourcekeyname);
                return;
            }
            contentType = jobj.get("contentType").getAsString();
            size = jobj.get("size").getAsString();
            // if we need them
            //bucket = jobj.get("bucket").getAsString();
            //created = jobj.get("timeCreated").getAsString();
            //udpated = jobj.get("updated").getAsString();
        } catch (Exception e) {}
        String eventType = event.getType();
        switch(eventType) {
            case "google.cloud.storage.object.v1.finalized":
                if (name != null && !name.isEmpty()) {
                    System.out.print("Received finalize event for: " + name + " type: " + contentType + " size: " + size );
                    if (watchesWithDependencies.containsKey(datasourcekeyname)){
                        Dependencies d = watchesWithDependencies.get(datasourcekeyname);
                        String rootKey = d.getRootFile(name); // this is the filename root we need to match
                        // TESTING TODO REMOVE PRINTOUTS WHEN DONE
                        System.out.println("Root: " + rootKey);
                        if (rootKey == null) {
                            System.err.println("RootKey is null for : " + name);
                            return;
                        }
                        System.out.println(String.format("Dependencies: %s from %s and %s", rootKey, datasourcekeyname, name));
                        Dependencies.addOrUpdateName(ds, rootKey, name);
                        List<String> foundfiles = Dependencies.listNames(ds, rootKey);
                        List<String> extensions = new ArrayList<>(d.extensions);
                        for (String f : foundfiles) {
                            System.out.println("Found dependency file: " + f);
                            extensions.removeIf(extension -> f.endsWith(extension));
                        }
                        if (!extensions.isEmpty()) {
                            System.out.println("... waiting for: " + rootKey + extensions);
                            return;
                        } else {
                            System.out.println("... Found all dependencies.  Keyfile = " + d.getKeyFile(foundfiles));
                            name = d.getKeyFile(foundfiles); // ensure message is only for key file
                            Dependencies.deleteAllNames(ds, rootKey);
                        }
                    }
                    System.out.println("... sending to " + mqSubjectOut);
                    mqProcessor.sendMessage(mqSubjectOut, name);
                } else {
                    try {
                        System.err.println("Could not retrieve name from " + new String(event.getData().toBytes()));
                    } catch (Exception e) {
                        System.err.println("Error parsing event data: " + e.getMessage());
                    }
                }
                break;
            default:
                System.out.println("Received unknown event type: " + eventType);
        }
    }

    static class Dependencies {
        String keyFileExtension;
        ArrayList<String> extensions;
        char delimiter;
        static final String maincollection = "watches";
        static final String doccollection = "names";
        static final String namefield = "name";

        Dependencies(char delimiter, String keyFileExtension, String... extensions) {
            this.delimiter = delimiter;
            this.keyFileExtension = keyFileExtension;
            this.extensions = new ArrayList<>();
            for (String extension : extensions) {
                this.extensions.add(extension);
            }
        }
        String getKeyFile(List<String> files) {
            for (String f : files) {
                if (f.endsWith(keyFileExtension)) {
                    return f;
                }
            }
            System.err.println("Could not find key file: " + files + " (" + keyFileExtension + ")");
            return null;
        }
        String getRootFile(String filename) {
            try {
                filename = filename.replaceAll(".*/", "");  // remove all pathing ...
                return filename.substring(0, filename.lastIndexOf(delimiter));
            } catch (StringIndexOutOfBoundsException e) {
                System.err.println("Invalid file format for: " + filename + " missing " + delimiter);
                return null;
            }
        }
        String getExtension(String filename) {
            try {
                return filename.substring(filename.lastIndexOf(delimiter));
            } catch (StringIndexOutOfBoundsException e) {
                System.err.println("Invalid file format for: " + filename + " missing " + delimiter);
                return null;
            }
        }
        ArrayList<String> getExtensions(String filename) {
            return extensions;
        }

        /**
         * firestore structure:
         * watches (main)/
         *   {dkey}/ (a document root e.g. jamaABCDE
         *     names/  (a subcollection under the document)
         *       {docId}  (a single document)  e.g. jamaABCDE_xml.zip
         *         value: {name}
         *         updatedAt: {timestamp}
         *
         *  Each node in Firestore is either a collection or a document — never both at once.
         *  each docId corresponds with exactly 1 document
         * */
        private static String sanitizeId(String s) {
            return s.replace("/", "~");
        }
        static Key watchKey(Datastore ds, String dkey) {
            return ds.newKeyFactory().setKind("Watch").newKey(sanitizeId(dkey));
        }

        static Key nameKey(Datastore ds, String dkey, String name) {
            return ds.newKeyFactory()
                    .addAncestor(PathElement.of("Watch", sanitizeId(dkey)))
                    .setKind("Name")
                    .newKey(sanitizeId(name));
        }

        public static void addOrUpdateName(Datastore ds, String dkey, String name) throws Exception {
            // NB: document fields are always maps
            Key key = nameKey(ds, dkey, name);
            //System.out.println("Adding Sanitized " + name + " => " + key.getName());
            // TTL (time to live) for automatic deletion
            Instant now = Instant.now();
            Instant ttl = now.plus(Duration.ofHours(6)); // 6h window should be good!

            Entity entity = Entity.newBuilder(key)
                    .set("value", StringValue.of(name))
                    .set("createdAt", TimestampValue.of(Timestamp.now()))
                    .set("expireAt", TimestampValue.of(Timestamp.ofTimeSecondsAndNanos(ttl.getEpochSecond(), 0)))
                    .build();
            ds.put(entity); // upsert
            //System.out.println("After Upsert");
        }

        public static List<String> listNames(Datastore ds, String dkey) throws Exception {
            Key ancestorKey = watchKey(ds, dkey);
            Query<Entity> q = Query.newEntityQueryBuilder()
                    .setKind("Name")
                    .setFilter(StructuredQuery.PropertyFilter.hasAncestor(ancestorKey))
                    .build();

            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            QueryResults<Entity> results = ds.run(q);
            while (results.hasNext()) {
                Entity e = results.next();
                out.add(e.contains("value") ? e.getString("value") : e.getKey().getName());
            }
            return out;
        }
        public static void deleteAllNames(Datastore ds, String rootKey) {
            Key ancestorKey = watchKey(ds, rootKey);

            Query<Entity> q = Query.newEntityQueryBuilder()
                    .setKind("Name")
                    .setFilter(StructuredQuery.PropertyFilter.hasAncestor(ancestorKey))
                    .build();

            QueryResults<Entity> results = ds.run(q);

            java.util.List<Key> keysToDelete = new java.util.ArrayList<>();

            while (results.hasNext()) {
                Entity e = results.next();
                keysToDelete.add(e.getKey());
            }

            if (!keysToDelete.isEmpty()) {
                System.out.printf("Deleting %d dependency entities under watch %s%n",
                        keysToDelete.size(), rootKey);
                ds.delete(keysToDelete.toArray(new Key[0]));
            } else {
                System.out.printf("No dependency entities found for %s%n", rootKey);
            }
        }
    }
}
