# rtu_cloudrun
cloud run function to kick off rtu upon upload trigger event

<h1>Cloud run functions are deployed to Google Cloud Run.</h1> 

> Cloud Run functions based on **Google Functions Framework for Java**  
<br>We are using Cloud Run Gen 2, which is recommended
<br>To create an event trigger handler, your java class specified in the docker file
must implement **CloudEventsFunction** and its **accept** override.  
<br>Very important note:  do not create a main() method in your EventFunctions class as
the Functions Framework must start the HTTP server (injects HTTP requests and CloudEvents
into the **accept** method).  The FunctionsFramework is a Java library that your 
application depends on and starts the HTTP server automatically.

- Step 1- build **docker** image
    - Note that must include both jars e.g. "-cp", "java-function-invoker-1.4.1.jar:rtu_cloudrun-1.0-SNAPSHOT-jar-with-dependencies.jar"
- Step 2- push to **artifact repository** (container=us-west2-docker.pkg.dev)
- Step 3- deploy to cloud run:
    - **gcloud run deploy** cloud-run-service-name \
    --image container/dd-production/repo/$appname:latest \
    --region us-west2 \
    --platform managed \
    --allow-unauthenticated

<br>note: cloud-run-service-name cannot contain _ or spaces, etc
<br>**repo = cloud-run-source-deploy** 
<br>**artifact container = us-west2-docker.pkg.dev** 


<br>Then create the Eventarc trigger and associate with your run-service:
>    **gcloud eventarc triggers create** gcs-trigger \
    --destination-run-service=cloud-run-service-name \
    --destination-run-region=us-west2 \
    --event-filters="type=google.cloud.storage.object.v1.finalized" \
    --event-filters="bucket=$bucket" \
    --location=us-west2

<br>There are many Eventarc triggers.  The above is for GCS uploads.
<br>gcloud eventarc triggers list --location=us-west2
NAME         TYPE                                      DESTINATION                      ACTIVE  LOCATION
<br>gcs-trigger  google.cloud.storage.object.v1.finalized  Cloud Run service: rtu-cloudrun  Yes     us-west2
<br>gcloud eventarc triggers describe gcs-trigger --location=us-west2

<br>To view Cloud Run Services:
https://console.cloud.google.com/artifacts/browse/dd-production
<br>look under repo name (above)

<br>**Docker file configuration**
><br>This is tricky as you have to include the Java Invoker.
<br>The Java Invoker is an HTTP server that forwards requests to your function.
<br>The Functions Invoker has very strict reqirements**
<br>You can create a fat shaded jar but I had problems with that.
<br>Now I am including the Invoker Jar separately in the classpath.
<br>Pros: Less to wire; Google maintains the invoker & runtime image; aligns with “functions” buildpacks flow.
<br>Cons: Slightly less control over the base; image size may be larger than a minimal JRE + shaded jar.
<br><br>**In order to include the Invoker Jar:** 
>><br>You have to first copy the invoker to a local directory:
>><br>mvn dependency:copy -Dartifact=com.google.cloud.functions.invoker:java-function-invoker:1.4.1 -DoutputDirectory=libs
<br>Then ensure the Docker file explicitly adds this jar:
<br>ADD libs/java-function-invoker-1.4.1.jar /usr/src/

To test locally:
<br>mvn com.google.cloud.functions:function-maven-plugin:0.10.1:run \
-Drun.functionTarget=com.deepdyve.rtu_cloudrun.RunOnUpload

<br>Please also note:  to access nats from cloudrun you must use a VPC in the deploy command
<br>--vpc-connector cloud-func-to-internal \
--vpc-egress all-traffic \

**Datastore**
Uses <locator>https://console.cloud.google.com/datastore/databases</locator>
default datastore database to hold dependencies for multiple linked file uploads
e.g. for jama (_xml and _pdf zips)
<br>Firestore with Datastore compatibility
<br>Please note that TTL policy is configured for this 

