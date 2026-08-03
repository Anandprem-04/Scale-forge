from fastapi import FastAPI, HTTPException, Header
from pydantic import BaseModel
from typing import List
import numpy as np
from sklearn.linear_model import LinearRegression

app = FastAPI(title="ScaleForge ML Service", version="1.0.0")

# Secure API Key for inter-service communication
API_KEY = "scaleforge-secure-token-12345"

class Snapshot(BaseModel):
    timestamp: str
    avgCpu: float
    avgMemory: float
    activeNodes: int
    activeReplicas: int

class PredictRequest(BaseModel):
    history: List[Snapshot]

class PredictResponse(BaseModel):
    predictedCpu: float
    confidence: float

@app.get("/health")
def health():
    return {"status": "UP"}

@app.post("/predict", response_model=PredictResponse)
def predict(request: PredictRequest, x_api_key: str = Header(default=None)):
    if x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="Unauthorized API Access: Invalid API Key")
        
    history = request.history
    if not history:
        raise HTTPException(status_code=400, detail="History cannot be empty")
        
    if len(history) < 2:
        # Fallback if there aren't enough data points yet
        return PredictResponse(
            predictedCpu=history[-1].avgCpu if history else 0.0, 
            confidence=0.5
        )
        
    try:
        # Extract CPU values
        cpu_values = [s.avgCpu for s in history]
        x = np.arange(len(cpu_values)).reshape(-1, 1)
        y = np.array(cpu_values)
        
        # Train a dynamic linear regression model on the snapshot history
        model = LinearRegression()
        model.fit(x, y)
        
        # Forecast for the next interval
        next_step = np.array([[len(cpu_values)]])
        predicted_cpu = float(model.predict(next_step)[0])
        
        # Clamp CPU value between 0.0 and 100.0%
        predicted_cpu = max(0.0, min(100.0, predicted_cpu))
        
        # Calculate fit confidence based on MSE
        y_pred = model.predict(x)
        mse = float(np.mean((y - y_pred) ** 2))
        confidence = float(1.0 / (1.0 + mse)) if mse > 0 else 1.0
        
        return PredictResponse(
            predictedCpu=predicted_cpu,
            confidence=confidence
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Forecasting engine failure: {str(e)}")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
