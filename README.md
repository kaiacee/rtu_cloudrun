# rtu_cloudrun
cloud run function to kick off rtu upon upload trigger event

Cloud run functions are deployed to Google Cloud Run.

Step 1- build **docker** image
<br>Step 2- push to **artifact repository** (container=us-west2-docker.pkg.dev)
<br>Step 3- deploy to cloud run:
<br>    gcloud run deploy cloud_run_service_name \
    --image $container/dd-production/$repo/$appname:latest \
    --region us-west2 \
    --platform managed \
    --allow-unauthenticated

<br>To create an event trigger handler, your java class specified in the docker file 
must implement **CloudEventsFunction** and its **accept** override

<br>Then create the Eventarc trigger and associate with your run-service:
    gcloud eventarc triggers create gcs-trigger \
    --destination-run-service=$appname \
    --destination-run-region=us-west2 \
    --event-filters="type=google.cloud.storage.object.v1.finalized" \
    --event-filters="bucket=$bucket" \
    --location=us-west2

<br>There are many Eventarc triggers.  The above is for GCS uploads.

<br>To view Cloud Run Services:
https://console.cloud.google.com/artifacts/browse/dd-production
**repo = cloud-run-source-deploy**

