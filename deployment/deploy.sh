#!/usr/bin/env sh

set -e  # Exit on error

echo "Building Docker image with Jib..."
(./gradlew :app:jibBuildTar --no-configuration-cache && docker load --input app/build/jib-image.tar)

echo "Loading Docker image from tar..."

echo "Tagging image for GCP Artifact Registry..."
docker tag ghcr.io/geoffsee/graalfaas:latest us-central1-docker.pkg.dev/$GCP_PROJECT_ID/graalfaas/graalfaas:latest

echo "Pushing image to GCP Artifact Registry..."
docker push us-central1-docker.pkg.dev/$GCP_PROJECT_ID/graalfaas/graalfaas:latest

echo "Deploying to Cloud Run..."
gcloud run deploy graalfaas --region=us-central1 --platform=managed --allow-unauthenticated \
      --port=8080 --image=us-central1-docker.pkg.dev/$GCP_PROJECT_ID/graalfaas/graalfaas:latest \
      --min-instances=0 --max-instances=3 --cpu=1 --memory=512M

echo "Deployment complete!"

