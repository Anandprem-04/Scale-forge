import os
import time
import requests
import threading
from fastapi import FastAPI, HTTPException

app = FastAPI(title="ScaleForge Target Service", version="1.0.0")

# Read environment configurations
CONTROLLER_URL = os.getenv("SCALEFORGE_CONTROLLER_URL", "http://localhost:8080/api/metrics/report")
API_KEY = os.getenv("SCALEFORGE_API_KEY", "scaleforge-secure-token-12345")
CONTAINER_ID = os.getenv("HOSTNAME", "local-replica-1")

is_burning = False
burn_lock = threading.Lock()

def cpu_burner(duration_seconds: int):
    global is_burning
    end_time = time.time() + duration_seconds
    while time.time() < end_time:
        # Spend 50ms doing intensive math, then yield 10ms
        chunk_end = time.time() + 0.05
        while time.time() < chunk_end:
            _ = 12345.67 * 76543.21
        time.sleep(0.01)
    
    with burn_lock:
        is_burning = False
    print("Simulated CPU load finished.")

def push_metrics_loop():
    print("Background metrics push thread started...")
    while True:
        try:
            # Measure local CPU
            cpu_val = 91.8 if is_burning else 3.5
            payload = {
                "containerId": CONTAINER_ID,
                "cpuPercent": cpu_val,
                "memoryUsageMb": 18.2
            }
            headers = {
                "X-API-KEY": API_KEY,
                "Content-Type": "application/json"
            }
            # Push metrics to Spring Boot Controller
            response = requests.post(CONTROLLER_URL, json=payload, headers=headers, timeout=5)
            if response.status_code != 200:
                print(f"Controller rejected metrics: {response.status_code}")
        except Exception as e:
            print(f"Failed to push metrics to controller: {e}")
        time.sleep(10)

# Start background push loop
push_thread = threading.Thread(target=push_metrics_loop)
push_thread.daemon = True
push_thread.start()

@app.get("/health")
def health():
    return {"status": "UP", "timestamp": time.time()}

@app.get("/metrics")
def get_metrics():
    # Keep HTTP get metrics endpoint available as fallback
    return {
        "cpuPercent": 91.8 if is_burning else 3.5,
        "memoryUsageMb": 18.2,
        "timestamp": time.time()
    }

@app.post("/load")
def load(seconds: int = 15):
    global is_burning
    with burn_lock:
        if is_burning:
            raise HTTPException(status_code=409, detail="CPU load simulation already active.")
        is_burning = True
        
    print(f"Starting CPU load simulation for {seconds} seconds...")
    thread = threading.Thread(target=cpu_burner, args=(seconds,))
    thread.daemon = True
    thread.start()
    
    return {"message": f"Simulated load spike started for {seconds} seconds."}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8081)
