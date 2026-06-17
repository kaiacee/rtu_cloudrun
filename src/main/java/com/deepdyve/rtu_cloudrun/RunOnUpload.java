package com.deepdyve.rtu_cloudrun;

import com.google.cloud.Timestamp;
import com.google.cloud.datastore.*;
import com.google.cloud.functions.CloudEventsFunction;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.cloudevents.CloudEvent;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import static com.deepdyve.rtu_cloudrun.Constants.*;

public class RunOnUpload implements CloudEventsFunction {
    private static MQProcessor mqProcessor;
    private static Datastore ds = DatastoreOptions.getDefaultInstance().getService(); // uses ADC on Cloud Run
    private static final String ARCHIVEDATEFORMAT = "yyyyMM";


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
        /* to note: scrapes (not included in watches)
            berhahn, asbmb, cob, arxiv [crawl0 & 1]
            chi, plos_journal, chemrxiv, medrxiv, springer_pubs
            ajnr, du_press, rockefeller, ejtr [crawl3]

            sftp/iop/.. sftp/utp/.. sftp/acs/.. can be removed
         */
        /* Q's:
            2 allen press FTPs, both seem to be getting content !
            cclh -- active ?
            aacr -- NOT active
            iwa ??
            aacc ?
            ou_press has an SFTP site but its not used !
         */
        watches = new ArrayList<>();
        watches.add("aea");
        watches.add("aip");
        watches.add("acm");
        watches.add("allen_press");
        watches.add("ams");
        watches.add("aps");
        watches.add("bmj");
        watches.add("brill");
        watches.add("bioscientifica");
        watches.add("cabi");
        // cclh?
        watches.add("csiro");
        watches.add("cup");
        watches.add("cu_press");
        watches.add("degruyter");
        watches.add("emerald");
        watches.add("emerald_books");
        watches.add("ers");
        watches.add("guilford");
        watches.add("inderscience");
        watches.add("informs");
        watches.add("iop2");
        watches.add("iop_books");
        watches.add("karger");
        watches.add("kli");
        watches.add("mag");
        watches.add("mit_press");
        watches.add("eupress");
        watches.add("nejm");
        watches.add("ou_press");
        //watches.add("pmc"); for new PMC format 20260414 now does not come in gzs but instead comes in xml/pdf pairs
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
        watches.add("uc_press");
        watches.add("unc_press");
        watches.add("utp");
        watches.add("wiley");
        watches.add("wiley_books");
        watches.add("wolters_kluwer");
        watches.add("wspc");
        watchesWithDependencies = new HashMap<>();
        watchesWithDependencies.put("jama", new Dependencies('_',"_xml.zip", "_xml.zip", "_pdf.zip"));
        watchesWithDependencies.put("imanager",  new Dependencies('.', ".txt",".txt", ".pdf"));
        watchesWithDependencies.put("pmc",  new Dependencies('.', ".xml", new String[]{".xml"}, ".pdf"));
        watchesWithDependencies.put("project_muse", new Dependencies('_',"_journals_metadata.tar.gz", "_journals_metadata.tar.gz", "_journals_full.tar.gz"));
        // moved to sage - watchesWithDependencies.put("iospress", new Dependencies('.', ".xml", ".xml", ".pdf"));

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
        String datasourcekeyname = null, bucket = null;
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
            bucket = jobj.get("bucket").getAsString();
            //created = jobj.get("timeCreated").getAsString();
            //udpated = jobj.get("updated").getAsString();
        } catch (Exception e) {}
        String eventType = event.getType();
        switch(eventType) {
            case "google.cloud.storage.object.v1.finalized":
                if (name != null && !name.isEmpty()) {
                    System.out.println("Received: " + name /*+ " type: " + contentType*/ + " size: " + size  /*+ " received"*/);
                    if (watchesWithDependencies.containsKey(datasourcekeyname)){
                        Dependencies d = watchesWithDependencies.get(datasourcekeyname);
                        if (d.isOptionalFile(name)) {
                            if (VERBOSE) {
                                System.out.println("Ignoring optional dependency file: " + name);
                            }
                            return;
                        }
                        if (!d.isRequiredFile(name)) {
                            String dateSuffix = new SimpleDateFormat(ARCHIVEDATEFORMAT).format(new Date());
                            String prefix = datasourcekeyname + "/";
                            String pathOnGCS = name.startsWith(prefix)
                                    ? prefix + dateSuffix + "/" + name.substring(prefix.length())
                                    : name;
                            moveNonDependencyFile(name, pathOnGCS);
                            return;
                        }
                        if (d.requiresDatastoreTracking()) {
                            String rootKey = d.getRootFile(name); // this is the filename root we need to match
                            if (VERBOSE) {
                                System.out.println("Root: " + rootKey);
                            }
                            if (rootKey == null) {
                                System.err.println("RootKey is null for : " + name);
                                return;
                            }
                            if (VERBOSE) {
                                System.out.printf("Dependencies: %s from %s and %s%n", rootKey, datasourcekeyname, name);
                            }
                            Dependencies.addOrUpdateName(ds, rootKey, name);
                            List<String> foundfiles = Dependencies.listNames(ds, rootKey);
                            List<String> extensions = new ArrayList<>(d.requiredExtensions);
                            for (String f : foundfiles) {
                                extensions.removeIf(extension -> f.endsWith(extension));
                            }
                            // if don't have required dependency file (via extension match), then wait
                            if (!extensions.isEmpty()) {
                                if (VERBOSE) {
                                    System.out.println("... waiting for: " + rootKey + extensions);
                                }
                                return;
                            } else {
                                System.out.println("... Found all dependencies.  Keyfile = " + d.getKeyFile(foundfiles) + "=>" + d.getDependentFile(foundfiles));
                                name = d.getKeyFile(foundfiles); // ensure message is only for key file
                                Dependencies.deleteAllNames(ds, rootKey);
                            }
                        } else if (VERBOSE) {
                            System.out.println("... Found required keyfile with optional companions: " + name);
                        }
                    }
                    // TODO: ONLY FOR QA-PROD TESTING PURPOSES - IMPORTANT REMOVE WHEN DONE !!!!
                    //if (QA) {   // this is QA-PROD testing and is *only* for initial testing strategies
                    //    GCSUtils.gcsMove(gcsQaBucket, name, gcsStaging, name);
                        //System.out.println("TESTING ONLY!! MOVING: " + name + " to " + gcsStaging);
                    //}
                    // TODO: remove mqUrl when done testing
                    System.out.println("to " + mqSubjectOut + " " + name  + " (" + mqUrl + ")");
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
        ArrayList<String> requiredExtensions;
        ArrayList<String> optionalExtensions;
        char delimiter;
        static final String maincollection = "watches";
        static final String doccollection = "names";
        static final String namefield = "name";

        Dependencies(char delimiter, String keyFileExtension, String... extensions) {
            this(delimiter, keyFileExtension, extensions, new String[0]);
        }

        Dependencies(char delimiter, String keyFileExtension, String[] requiredExtensions, String... optionalExtensions) {
            this.delimiter = delimiter;
            this.keyFileExtension = keyFileExtension;
            this.requiredExtensions = new ArrayList<>();
            this.optionalExtensions = new ArrayList<>();
            for (String extension : requiredExtensions) {
                this.requiredExtensions.add(extension);
            }
            for (String extension : optionalExtensions) {
                this.optionalExtensions.add(extension);
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
        // for debugging
        String getDependentFile(List<String> files) {
            for (String f : files) {
                if (!f.endsWith(keyFileExtension) && requiredExtensions.stream().anyMatch(f::endsWith)) {
                    return f;
                }
            }
            System.err.println("Could not find dependency file for " + getKeyFile(files));
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
            return requiredExtensions;
        }

        boolean isRequiredFile(String filename) {
            return requiredExtensions.stream().anyMatch(filename::endsWith);
        }

        boolean isOptionalFile(String filename) {
            return optionalExtensions.stream().anyMatch(filename::endsWith);
        }

        boolean requiresDatastoreTracking() {
            return requiredExtensions.size() > 1;
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

    private static void moveNonDependencyFile( String src, String dest) {
        try {
            GCSUtils.gcsMove(gcsStaging, src, gcsArchive, dest);
            System.out.println("NON_DEPENDENCY_MOVE source=gs://" + gcsStaging + "/" + src +
                    " -> gs://" + gcsArchive + "/" + dest);
        } catch (Exception e) {
            System.err.println("Failed moving non-dependency file " + src + ": " + e.getMessage());
        }
    }
}
