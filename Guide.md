# ScaleForge - Autonomous Capacity Management Platform

ScaleForge is a platform-agnostic, autonomous capacity management infrastructure. It continuously monitors target application workloads, aggregates telemetry data, forecasts future demand using machine learning, and executes safe, cost-efficient scaling actions (replicas and cloud nodes) to match traffic demand.

---

## 1. Core Capacity Philosophy

Most autoscalers make a fundamental mistake: **CPU High $\rightarrow$ Add VM Node**.
However, adding a node alone does not increase service throughput if container replicas remain on the original node. ScaleForge treats capacity as two distinct layers:

1. **Service Capacity (Replicas)**: What actually handles client requests (throughput).
2. **Infrastructure Capacity (Nodes)**: What provides compute resources (CPU/Memory).

### The Scaling Hierarchy:
```text
Need More Capacity?
       ↓
Can existing nodes host more replicas?
       ├── YES ──> Scale Replicas (docker service scale target=N+1)
       └── NO  ──> Scale Nodes (Launch EC2 Instance) ──> Join Swarm ──> Deploy Replica
```

---

## 2. System Architecture

ScaleForge is designed as a **decoupled service-oriented architecture** consisting of four primary components:

* **ScaleForge Controller (Java Spring Boot 3.x)**: The brain of the platform. Collects metric reports, computes snapshots, checks policy constraints, evaluates decision-making logic, and executes scales.
* **ML Forecasting Service (Python FastAPI & Scikit-Learn)**: Receives time-series snapshots and uses Linear Regression trend modeling to predict CPU usage for the next cycle.
* **Nginx Reverse Proxy**: The public-facing load balancer routing incoming client traffic to the Target Service containers.
* **Target Service (Python FastAPI)**: The replaceable application workload running inside Docker Swarm.

---

## 3. Directory Map

Here is the repository structure:

```text
scaleforge/
├── backend/                       # ScaleForge Controller (Spring Boot)
│   ├── pom.xml                    # Maven build configuration
│   └── src/main/java/...          # Controller logic, Security filters, and Schedulers
├── ml-service/                    # Forecasting Service (FastAPI)
│   ├── main.py                    # Prediction REST endpoint and ML trend model
│   └── requirements.txt           # Python libraries (scikit-learn, fastapi, uvicorn)
├── target-service/                # Replaceable Workload Service (FastAPI)
│   ├── app.py                     # App code exposing CPU load generator & 10s metric push loop
│   ├── requirements.txt           # App dependencies
│   └── Dockerfile                 # Docker packaging setup
├── proxy/                         # Load Balancer Layer
│   └── nginx.conf                 # Dynamic Nginx upstream configurations
└── README.md                      # This onboarding guide
```

---

## 4. Onboarding Guide: Running Locally (Sandbox Setup)

To run ScaleForge locally for testing, we run in **Sandbox Mode** (where AWS and Swarm commands are simulated/mocked). Follow these steps:

### Prerequisites
1. **Java Development Kit (JDK) 17**
2. **Apache Maven 3.8+**
3. **Python 3.10+** (with `pip`)

### Step 1: Run the ML Forecasting Service
1. Open a terminal and navigate to `ml-service/`.
2. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```
3. Start the FastAPI server:
   ```bash
   python main.py
   ```
   *The ML service will start listening on `http://localhost:8000`.*

### Step 2: Run the Target Workload Service
1. Open a second terminal and navigate to `target-service/`.
2. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```
3. Start the target service:
   ```bash
   python app.py
   ```
   *The target service starts on `http://localhost:8081` and immediately begins its background metrics push loop to the controller.*

### Step 3: Start the ScaleForge Controller
1. Open a third terminal and navigate to `backend/`.
2. Clean and build the application:
   ```bash
   mvn clean package
   ```
3. Run the Spring Boot application:
   ```bash
   mvn spring-boot:run
   ```
   *The controller starts on `http://localhost:8080` (with H2 database running in-memory).*

### Step 4: Open the Web Dashboard
1. Open your browser and navigate to: `http://localhost:8080/dashboard`
2. You will see live telemetry graphs showing:
   - Aggregated Average CPU & Memory
   - Count of active Replicas & Nodes
   - Real-time audit logs of scaling decisions.

---

## 5. Vulnerability Mitigations & Core Safety Rules

### 5.1 Unauthorized API Access
To prevent attackers from triggering false scaling actions (which would drive up AWS EC2 bills) or shutting down target replicas (causing outages), all inter-service APIs are protected via an **`X-API-KEY` header**. The FastAPI ML Service, target container metric push API, and registration API reject any requests lacking a valid key.

### 5.2 Oscillation & Flapping Prevention
Sudden load changes can cause rapid scaling up and down cycles, wasting cloud resources. ScaleForge solves this using:
- **Hysteresis**: Scale-up occurs at **$>80\%$ CPU**, scale-down occurs at **$<35\%$ CPU**.
- **Cooldown State Locks**: Once a scale-up action runs, the system is locked into a `COOLDOWN` state for **10 minutes** (no scaling allowed). Once a scale-down action runs, the system is locked for **3 minutes**.

### 5.3 Zombie Instance Detection
If a new EC2 worker node is launched but fails to join the Swarm cluster within **7 minutes** (due to network or boot failures), ScaleForge's bootstrap registry marks the node as a `ZOMBIE` and automatically calls the AWS API to terminate it to stop billing leakage.

### 5.4 Idempotent Actions
To prevent duplicate VM launches or replica scales during network timeouts:
* **AWS SDK**: ScaleForge generates a UUID for the decision and passes it as the `clientToken` in the EC2 `RunInstances` request. Retried calls resolve safely on the existing instance.
* **Swarm Scales**: Scale calls are declarative (`docker scale=N`) rather than incremental, making retries safe.

---

## 6. How to Simulate a Scale Event

To watch ScaleForge scale in real-time on your local machine:
1. Open the dashboard at `http://localhost:8080/dashboard`.
2. Trigger a simulated high CPU load spike by executing a POST request to the Target Service:
   ```bash
   curl -X POST "http://localhost:8081/load?seconds=60"
   ```
3. Go back to the dashboard. You will see:
   - Target service CPU jump to $\sim 91\%$ on the metrics graph.
   - Within 60 seconds, the Broker evaluates the spike and outputs a `SCALE_UP` action.
   - The Policy Engine validates the action, generates a unique `idempotency_key`, and approves it.
   - The Execution layer scales up mock replicas, reloads Nginx, logs the event in the append-only audit log, and enters `COOLDOWN` lock mode.
