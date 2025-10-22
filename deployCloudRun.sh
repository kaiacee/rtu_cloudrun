container=us-west2-docker.pkg.dev
repo=cloud-run-source-deploy  
appname=rtu_cloudrun
servicename=rtu-cloudrun
if [[ "$1" == "eventarc" ]]; then
	eventarc=true
	bucket=rt-upload-staging_qa
fi
if [[ "$eventarc" != "true"  ]]; then
	#build docker image - note: cloudrun is amd64
	docker buildx build --platform linux/amd64 -t $container/dd-production/$repo/$appname:latest -f dockerfiles/Dockerfile .

	#push docker image to repository
	docker push $container/dd-production/$repo/$appname:latest

	#deploy cloud run service
	gcloud run deploy $servicename \
  	--image $container/dd-production/$repo/$appname:latest \
  	--region us-west2 \
  	--platform managed \
	--vpc-connector cloud-func-to-internal \
        --vpc-egress all-traffic \
  	--allow-unauthenticated
fi
if [[ "$eventarc" == "true"  ]]; then
	#Eventarc triggers remain intact until you explicitly delete or modify them.
	#change only when event routing changes
	gcloud eventarc triggers create gcs-trigger \
  --destination-run-service=$servicename \
  --destination-run-region=us-west2 \
  --event-filters="type=google.cloud.storage.object.v1.finalized" \
  --event-filters="bucket=$bucket" \
  --service-account="content-access@dd-production.iam.gserviceaccount.com" \
  --location=us-west2
	
fi
