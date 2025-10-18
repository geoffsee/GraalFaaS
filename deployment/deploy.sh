#!/usr/bin/env sh

#GCP_PROJECT="k8s-clusters-472014" && export GCP_REGION="us-central1" && cdktf synth

gcloud run deploy graalfaas --region=us-central1 --platform=managed --allow-unauthenticated           \
      --port=8080 --image=us-central1-docker.pkg.dev/$GCP_PROJECT_ID/graalfaas/graalfaas:latest   \
      --min-instances=0 --max-instances=3 --cpu=1 --memory=512M