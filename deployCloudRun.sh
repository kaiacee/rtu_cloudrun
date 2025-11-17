qa=""
eventarc="false"
dtype=".prod"
verb="update"
if [[ "$#" -ge 1 ]]; then
	for ((i=1;i<=$#;i++));  do
		curarg="${!i}"
		if [ "$curarg" == "-qa" ]; then
			qa="_qa"
			dtype=""
			echo "QA chosen"
		elif [ "$curarg" == "-eventarc" ]; then
		  eventarc=true
			echo "Creating or Updating Eventarc"
		elif [ "$curarg" == "-c" ]; then
			verb=create
			echo "Creating new eventarc"
		fi
	done
fi



container=us-west2-docker.pkg.dev
repo=cloud-run-source-deploy
appname=rtu_cloudrun
servicename=rtu-cloudrun
if [[ "$eventarc" == "true" ]]; then
	bucket=rt-upload-staging$qa
fi
if [[ "$eventarc" != "true"  ]]; then
	#build docker image - note: cloudrun is amd64
	docker buildx build --platform linux/amd64 -t $container/dd-production/$repo/$appname:latest -f dockerfiles/Dockerfile$dtype .

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
	if [[ "$verb" == "create" ]]; then # delete existing first
		gcloud eventarc triggers delete gcs-trigger --location=us-west2
	fi
	#Eventarc triggers remain intact until you explicitly delete or modify them.
	#change only when event routing changes
	gcloud eventarc triggers $verb gcs-trigger \
  --destination-run-service=$servicename \
  --destination-run-region=us-west2 \
  --event-filters="type=google.cloud.storage.object.v1.finalized" \
  --event-filters="bucket=$bucket" \
  --service-account="content-access@dd-production.iam.gserviceaccount.com" \
  --location=us-west2
	
fi
