import os
import logging
from flask import Flask, jsonify, request
from google.cloud import storage

# Configure logging level from environment variable
LOG_LEVEL = os.environ.get('LOG_LEVEL', 'DEBUG').upper()
logging.basicConfig(
    level=getattr(logging, LOG_LEVEL),
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)

# Enable debug logging for Google libraries when in DEBUG mode
if LOG_LEVEL == 'DEBUG':
    logging.getLogger('google.auth').setLevel(logging.DEBUG)
    logging.getLogger('google.auth.transport').setLevel(logging.DEBUG)
    logging.getLogger('google.cloud.storage').setLevel(logging.DEBUG)
    logging.getLogger('google.oauth2').setLevel(logging.DEBUG)
    logging.getLogger('google.auth.external_account').setLevel(logging.DEBUG)
    logging.getLogger('google.auth.impersonated_credentials').setLevel(logging.DEBUG)
    logging.getLogger('google.auth._default').setLevel(logging.DEBUG)
    logging.getLogger('google.auth.identity_pool').setLevel(logging.DEBUG)

logger = logging.getLogger(__name__)

app = Flask(__name__)

# Configure Google Cloud Storage settings
BUCKET_NAME = os.environ.get("BUCKET_NAME", "hack-team-code-catalyst-openshift-demo")
PROJECT_ID = os.environ.get("PROJECT_ID", "hack-team-code-catalyst")

logger.info(f"Configured to use bucket: {BUCKET_NAME} in project: {PROJECT_ID}")

# Storage client will be initialized on first use
storage_client = None
bucket = None

def get_storage_client():
    """Initialize storage client on first use"""
    global storage_client, bucket
    if storage_client is None:
        try:
            logger.info("Initializing Google Cloud Storage client...")
            logger.debug(f"Project ID: {PROJECT_ID}")
            logger.debug(f"Bucket name: {BUCKET_NAME}")
            logger.debug(f"GOOGLE_APPLICATION_CREDENTIALS: {os.environ.get('GOOGLE_APPLICATION_CREDENTIALS')}")
            
            # Check if credential file exists
            cred_file = os.environ.get('GOOGLE_APPLICATION_CREDENTIALS')
            if cred_file and os.path.exists(cred_file):
                logger.debug(f"Credential file exists at {cred_file}")
                try:
                    with open(cred_file, 'r') as f:
                        import json
                        cred_content = json.load(f)
                        logger.debug(f"Credential file type: {cred_content.get('type')}")
                        logger.debug(f"Credential file keys: {list(cred_content.keys())}")
                except Exception as e:
                    logger.error(f"Error reading credential file: {e}")
            else:
                logger.warning(f"Credential file not found at {cred_file}")
            
            storage_client = storage.Client(project=PROJECT_ID)
            bucket = storage_client.bucket(BUCKET_NAME)
            logger.info("Storage client initialized successfully")
        except Exception as e:
            logger.error(f"Failed to initialize storage client: {str(e)}", exc_info=True)
            raise
    return storage_client, bucket

@app.route("/")
def home():
    """Health check endpoint"""
    return jsonify({
        "status": "healthy",
        "team": "code-catalyst",
        "project": PROJECT_ID,
        "bucket": BUCKET_NAME
    })

@app.route("/debug")
def debug():
    """Debug endpoint to check authentication setup"""
    debug_info = {
        "project": PROJECT_ID,
        "bucket": BUCKET_NAME,
        "log_level": LOG_LEVEL,
        "env_vars": {
            "GOOGLE_APPLICATION_CREDENTIALS": os.environ.get("GOOGLE_APPLICATION_CREDENTIALS"),
            "PROJECT_ID": os.environ.get("PROJECT_ID"),
            "BUCKET_NAME": os.environ.get("BUCKET_NAME"),
            "TEAM_NAME": os.environ.get("TEAM_NAME"),
            "LOG_LEVEL": os.environ.get("LOG_LEVEL")
        },
        "token_file_exists": os.path.exists("/var/run/secrets/kubernetes.io/serviceaccount/token"),
        "wif_config_exists": os.path.exists("/var/run/secrets/gcp/config.json"),
        "service_account_info": {}
    }
    
    # Check service account tokens at various locations
    token_paths = [
        "/var/run/secrets/kubernetes.io/serviceaccount/token",
        "/var/run/secrets/openshift/serviceaccount/token",
        "/var/run/service-account/token"
    ]
    
    debug_info["service_account_info"]["token_paths"] = {}
    
    for token_path in token_paths:
        path_info = {"exists": os.path.exists(token_path)}
        if path_info["exists"]:
            try:
                with open(token_path, "r") as f:
                    token = f.read()
                    path_info["token_length"] = len(token)
                    path_info["token_preview"] = token[:50] + "..." if len(token) > 50 else token
                    # Try to decode JWT header
                    try:
                        import base64
                        header = token.split('.')[0]
                        # Add padding if needed
                        header += '=' * (4 - len(header) % 4)
                        decoded = base64.b64decode(header)
                        path_info["jwt_header"] = json.loads(decoded)
                    except:
                        path_info["jwt_header"] = "Could not decode"
            except Exception as e:
                path_info["error"] = str(e)
        
        debug_info["service_account_info"]["token_paths"][token_path] = path_info
    
    # Try to read WIF config if it exists
    if debug_info["wif_config_exists"]:
        try:
            with open("/var/run/secrets/gcp/config.json", "r") as f:
                import json
                wif_config = json.load(f)
                debug_info["wif_config"] = {
                    "type": wif_config.get("type"),
                    "audience": wif_config.get("audience", "")[:100] + "..." if len(wif_config.get("audience", "")) > 100 else wif_config.get("audience"),
                    "subject_token_type": wif_config.get("subject_token_type"),
                    "service_account_impersonation_url": wif_config.get("service_account_impersonation_url", "")[:100] + "...",
                    "credential_source": wif_config.get("credential_source", {}),
                    "all_keys": list(wif_config.keys())
                }
        except Exception as e:
            debug_info["wif_config_error"] = str(e)
    
    # Try to initialize auth and see what happens
    try:
        from google.auth import default
        from google.auth.exceptions import DefaultCredentialsError
        
        credentials, project = default()
        debug_info["auth_test"] = {
            "success": True,
            "project_from_auth": project,
            "credential_type": type(credentials).__name__
        }
    except Exception as e:
        debug_info["auth_test"] = {
            "success": False,
            "error": str(e),
            "error_type": type(e).__name__
        }
    
    return jsonify(debug_info)

@app.route("/file/<filename>")
def get_file(filename):
    """
    GET endpoint to retrieve file contents from GCS bucket.
    
    Args:
        filename: Name of the file to retrieve from the bucket
    
    Returns:
        JSON response with file contents or error message
    """
    try:
        # Validate filename
        if not filename:
            return jsonify({"error": "Filename is required"}), 400
        
        # Prevent directory traversal attacks
        if ".." in filename or "/" in filename:
            return jsonify({"error": "Invalid filename"}), 400
        
        logger.info(f"Fetching file: {filename}")
        
        # Get storage client
        _, bucket = get_storage_client()
        
        # Get the blob from GCS
        blob = bucket.blob(filename)
        
        # Check if blob exists
        if not blob.exists():
            logger.warning(f"File not found: {filename}")
            return jsonify({"error": f"File '{filename}' not found"}), 404
        
        # Download the file contents
        contents = blob.download_as_text()
        
        logger.info(f"Successfully retrieved file: {filename}")
        
        return jsonify({
            "filename": filename,
            "contents": contents,
            "size": blob.size,
            "content_type": blob.content_type,
            "updated": blob.updated.isoformat() if blob.updated else None
        })
        
    except Exception as e:
        logger.error(f"Error retrieving file {filename}: {str(e)}")
        return jsonify({"error": f"Failed to retrieve file: {str(e)}"}), 500

@app.route("/files")
def list_files():
    """
    GET endpoint to list all available files in the bucket.
    
    Returns:
        JSON response with list of files
    """
    try:
        logger.info("Listing files in bucket")
        
        # Get storage client
        _, bucket = get_storage_client()
        
        # List all blobs in the bucket
        blobs = list(bucket.list_blobs(max_results=100))
        
        files = [
            {
                "name": blob.name,
                "size": blob.size,
                "content_type": blob.content_type,
                "updated": blob.updated.isoformat() if blob.updated else None
            }
            for blob in blobs
        ]
        
        logger.info(f"Found {len(files)} files")
        
        return jsonify({
            "bucket": BUCKET_NAME,
            "count": len(files),
            "files": files
        })
        
    except Exception as e:
        logger.error(f"Error listing files: {str(e)}")
        return jsonify({"error": f"Failed to list files: {str(e)}"}), 500

if __name__ == "__main__":
    # Get port from environment variable or default to 8080
    port = int(os.environ.get("PORT", 8080))
    
    # Run the Flask app
    app.run(host="0.0.0.0", port=port, debug=False)