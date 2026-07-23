FROM python:3.11-slim

# Set working directory
WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements and install Python dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY app.py .

# Create non-root user
RUN useradd -m -u 1001 appuser && chown -R appuser:appuser /app

# Switch to non-root user
USER 1001

# Expose port
EXPOSE 8080

# Set environment variables
ENV GOOGLE_APPLICATION_CREDENTIALS=/var/run/secrets/gcp/config.json
ENV PORT=8080

# Run the application with gunicorn
CMD ["gunicorn", "--bind", "0.0.0.0:8080", "--workers", "2", "--timeout", "120", "app:app"]