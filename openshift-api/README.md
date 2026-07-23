# Hackathon OpenShift API

This is a simple Python Flask API that demonstrates how to access Google Cloud Storage from an OpenShift application using Workload Identity Federation (WIF).

## Features

- **GET /**: Health check endpoint
- **GET /file/<filename>**: Retrieve a specific file from the shared GCS bucket
- **GET /files**: List all available files in the shared GCS bucket

## Authentication

This application uses Workload Identity Federation to authenticate with Google Cloud Storage. The OpenShift service account is configured to impersonate your team's GCP workload service account, providing secure access without managing service account keys.

## Local Development

1. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```

2. Set up authentication (for local development only):
   ```bash
   gcloud auth login --update-adc
   ```

3. Run the application:
   ```bash
   python app.py
   ```

## Building and Deploying

The application is automatically built and deployed to OpenShift via GitHub Actions when you push to the main branch.

### Manual Deployment

If you need to deploy manually:

1. Build the Docker image:
   ```bash
   docker build -t hackathon-api:latest .
   ```

2. Apply the deployment manifest:
   ```bash
   oc apply -f deployment.yaml
   ```

## API Examples

### Health Check
```bash
curl https://hackathon-api-code-catalyst.apps.dbhackathon.swedencentral.aroapp.io/
```

### Get a File
```bash
curl https://hackathon-api-code-catalyst.apps.dbhackathon.swedencentral.aroapp.io/file/TestFile.txt
```

### List Files
```bash
curl https://hackathon-api-code-catalyst.apps.dbhackathon.swedencentral.aroapp.io/files
```

## Troubleshooting

For comprehensive debugging instructions, please see the [DEBUGGING.md](DEBUGGING.md) guide which includes:
- Step-by-step debugging instructions
- How to use the `/debug` endpoint for diagnostics
- How to run the `test-wif.py` diagnostic script
- Common issues and their solutions
- Manual testing procedures

### Quick Commands

1. **Check pod status**:
   ```bash
   oc get pods -n code-catalyst-official
   ```

2. **View application logs**:
   ```bash
   oc logs -n code-catalyst-official deployment/hackathon-api
   ```

3. **Access debug endpoint**:
   ```bash
   ROUTE_URL=$(oc get route hackathon-api -n code-catalyst-official -o jsonpath='{.spec.host}')
   curl -k https://$ROUTE_URL/debug | jq .
   ```

4. **Run diagnostic test**:
   ```bash
   oc exec -n code-catalyst-official deployment/hackathon-api -- python /app/test-wif.py
   ```