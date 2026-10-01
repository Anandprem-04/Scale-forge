## What Is ScaleForge?
 
ScaleForge is an **infrastructure control plane** that automatically manages the capacity of a Docker Swarm cluster running on AWS EC2.
 
It is not a business application. It does not serve user traffic. It sits one level below your application entirely — watching the nodes your application runs on, and making intelligent decisions about when to add or remove infrastructure.
 
```
Your Users
    ↓
Your Application  (Spring Boot / Node.js / Python / anything)
    ↓
Docker Swarm Cluster  (containers, services, replicas)
    ↓
EC2 Nodes  (the actual machines)
    ↑
    └──  ScaleForge manages THIS layer only
```
 
When cluster CPU trends high, ScaleForge provisions a new EC2 instance and joins it to the Swarm. When nodes are underutilized, it safely drains and terminates one. Your application keeps running through all of this — it has no idea ScaleForge exists.
 
ScaleForge is **workload-agnostic**. Whether the cluster is running a Spring Boot API, a Python service, an Nginx container, or anything else — ScaleForge does not know and does not care. It only watches infrastructure metrics.
 
---
 
## Why Build This?
 
Modern applications face a fundamental infrastructure dilemma.
 
```
Under-provisioned  →  traffic spikes cause degradation and downtime
Over-provisioned   →  idle servers waste money every hour they run
```
 
Platforms like Kubernetes solve this problem at production scale. But how do they actually work?
 
ScaleForge is the answer to that question. It is a ground-up implementation of the core mechanics behind intelligent infrastructure management:
 
- How does a system decide when to scale?
- How do you safely remove a node without dropping requests?
- How do you predict load before thresholds are breached?
- How do you maintain consistent state across restarts and failures?
This project is built to learn these concepts deeply — designed like a real-world engineering system, not a CRUD application.
 
---
 
## How It Works
 
ScaleForge runs a continuous feedback loop:
 
```
  ┌─────────────────────────────────────────────────────┐
  ↓                                                     |
Observe → Analyze → Predict → Decide → Act ────────────┘
```
 
| Step | What Happens |
|---|---|
| **Observe** | Node agents on each EC2 instance push CPU, memory, and network metrics to ScaleForge every 30 seconds |
| **Analyze** | The Aggregation module converts per-node metrics into a single ClusterSnapshot representing overall health |
| **Predict** | The ML Prediction Service forecasts future resource demand based on historical metric trends |
| **Decide** | The Broker evaluates current + predicted state against thresholds and cooldown rules → `SCALE_UP` / `SCALE_DOWN` / `NO_ACTION` |
| **Act** | The Decision Engine executes the action via AWS EC2 and the Node Manager drives the full node lifecycle |
 
This loop runs every 60 seconds. All decisions are persisted to PostgreSQL for audit, analysis, and cooldown enforcement.
 
---
 
## Architecture
 
ScaleForge is a **modular monolith** — one deployable Spring Boot application organized into six independently-bounded modules, plus a separate Python prediction microservice.
 
```
┌──────────────────────────────────────────────────────────┐
│                   Infrastructure Layer                    │
│              Docker Swarm Cluster  ·  EC2 Nodes           │
└──────────────────────┬───────────────────────────────────┘
                       │  node agents push metrics (HTTP POST)
┌──────────────────────▼───────────────────────────────────┐
│                    Monitoring Module                      │
│         Receives · Validates · Persists node metrics      │
└──────────────────────┬───────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────┐
│                   Aggregation Module                      │
│        Computes ClusterSnapshot from recent metrics       │
└──────────────────────┬───────────────────────────────────┘
                       │
          ┌────────────▼────────────┐
          │      Broker Module      │◄──── Prediction Service (Python/FastAPI)
          │  Thresholds · Cooldowns │      Random Forest model
          │  → ScalingDecision      │      POST /predict  |  fallback: threshold-only
          └────────────┬────────────┘
                       │
┌──────────────────────▼───────────────────────────────────┐
│                   Decision Engine                         │
│     SCALE_UP → ProvisioningService → AWS EC2             │
│     SCALE_DOWN → NodeManager → drain → terminate         │
└──────────────────────┬───────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────┐
│                   Node Manager                            │
│   ACTIVE → DRAINING → READY_FOR_REMOVAL → TERMINATED     │
└──────────────────────┬───────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────┐
│                    Alert Module                           │
│      Structured alerts on critical infrastructure events  │
└──────────────────────────────────────────────────────────┘
```
 
### The Six Modules
 
| Module | Responsibility |
|---|---|
| `monitoring` | Receives metric POSTs from node agents, validates, persists to PostgreSQL |
| `aggregation` | Queries recent metrics, computes avg CPU / memory / capacity → `ClusterSnapshot` |
| `broker` | Evaluates `ClusterSnapshot` against `ScalingPolicy`, applies cooldowns, calls prediction service, emits `ScalingDecision` |
| `decision` | Translates decisions into AWS EC2 actions via `ProvisioningService` and `AwsEc2Client` |
| `nodemanager` | Drives the node state machine, enforces safety validation before removal, manages Docker Swarm join/leave |
| `alert` | Emits structured alerts on DB failure, provisioning failure, node going UNKNOWN |
 
---
 
## Node Lifecycle
 
Infrastructure is never removed abruptly. Every node follows a controlled state machine:
 
```
  PROVISIONING
       ↓
    ACTIVE  ──────────────────────────────────────┐
       ↓  (scale-down decision)                   │
   DRAINING  (stop routing new traffic)           │  (safety check fails
       ↓  (active connections reach zero)         │   → abort removal)
READY_FOR_REMOVAL                                 │
       ↓  (AWS terminate call succeeds)           │
  TERMINATED  ◄─────────────────────────────────-┘
 
  UNKNOWN  (metrics stop arriving → excluded from all decisions)
```
 
Before any node enters DRAINING, the ValidationService checks: can the cluster safely operate without this node? If not, removal is aborted. This check also exists as an early exit in the Broker — defense in depth across two layers.
 
---
 
## Scaling Safety
 
**Cooldown periods** — After any scaling event, a mandatory wait is enforced before the next decision cycle evaluates. Prevents scaling thrash.
 
**Node draining** — Traffic stops routing to a node before it is removed. Active connections complete. Zero dropped requests during scale-down.
 
**Minimum node guarantee** — The Broker exits early with `NO_ACTION` if the cluster is already at minimum node count. The ValidationService enforces the same check before physical removal.
 
**Startup warm-up** — On restart, ScaleForge waits for 2 full metric collection cycles before making any scaling decisions. Prevents acting on stale or incomplete data.
 
**When in doubt, do nothing** — Every failure path defaults to `NO_ACTION`. Stability is always preferred over an uncertain infrastructure action.
 
---
 
## Failure Handling
 
ScaleForge treats failures as expected events, not exceptions.
 
| Failure Scenario | Strategy |
|---|---|
| Prediction service unavailable | Fall back to threshold-only scaling — no decisions blocked |
| Database connection lost | Freeze all scaling decisions, retry connection, alert |
| AWS provisioning fails | Log structured error, retry once, alert — platform keeps running |
| Node termination fails | Node stays `READY_FOR_REMOVAL`, retried on next cycle |
| Node stops sending metrics | Marked `UNKNOWN` after 2 missed intervals, excluded from decisions |
| ScaleForge restarts | Rebuilds full state from PostgreSQL before starting decision loop |
 
---
 
## Security Model
 
ScaleForge's endpoints can trigger real infrastructure changes. Security is layered accordingly.
 
**API Key auth** — Each provisioned node receives a unique API key injected via bootstrap script. All metric POSTs require this key in `X-Api-Key`. Keys are stored hashed in PostgreSQL and invalidated when the node is terminated.
 
**JWT auth** — Admin and query endpoints (`/api/admin/*`, `/api/cluster/*`, `/api/nodes`) require a JWT token obtained via `/api/auth/login`.
 
**Shared secret** — The internal HTTP call from Broker to Prediction Service uses a shared secret. Never exposed externally.
 
**Rate limiting** — Node agent endpoints: max 5 req/min per node ID. Admin endpoints: max 30 req/min per IP. Auth endpoint: max 5 login attempts/min.
 
**Network topology** — ScaleForge runs in a private AWS subnet. Node agents communicate over the VPC internal network. Admin access via Nginx with IP allowlisting. PostgreSQL has no public IP.
 
---
 
## Tech Stack
 
| Layer | Technology |
|---|---|
| Core platform | Java 17, Spring Boot 3.x |
| Build tool | Maven |
| Database | PostgreSQL |
| DB migrations | Flyway |
| ORM | Spring Data JPA |
| Prediction service | Python, FastAPI, scikit-learn (Random Forest) |
| Cloud infrastructure | AWS EC2, AWS SDK for Java |
| Container orchestration | Docker Swarm |
| Reverse proxy | Nginx |
| Security | Spring Security, JWT |

---
 
## ML Cold Start Strategy
 
ScaleForge has no historical data when it first runs. This is solved with a two-phase approach:
 
**Phase 1 — Threshold-only scaling.** ScaleForge launches without the prediction service participating. The Broker makes decisions based purely on configured thresholds. Every metric collected is stored in PostgreSQL — this becomes the training dataset.
 
**Phase 2 — Synthetic bootstrap.** A `generate_data.py` script produces realistic metric sequences (normal traffic, traffic spikes, cooldown periods, idle patterns) with noise. The Random Forest model is trained on this immediately, giving the prediction service a working baseline from day one.
 
**Phase 3 — Real data retraining.** After weeks of real operation, `retrain.py` exports actual metrics from PostgreSQL and retrains the model. Predictions improve continuously over time.
 
---
 
## Key Design Decisions
 
**Push over pull for metrics** — Node agents POST metrics to ScaleForge every 30 seconds. ScaleForge never polls nodes. This scales linearly — 50 nodes means 50 agents pushing, not ScaleForge making 50 outbound calls per interval. Node silence after 2 missed intervals automatically triggers `UNKNOWN` detection.
 
**Modular monolith over microservices** — Six modules with clean internal boundaries in a single deployable artifact. Simpler to build, simpler to debug, and the seams are clean enough to split later if needed. The prediction service is the only true microservice boundary — justified by the language and runtime difference.
 
**Prediction as enhancement, not dependency** — The system scales correctly without the prediction service. Prediction improves decision confidence but is never a hard requirement. This is enforced at the `PredictionClient` level — timeout or failure returns a fallback signal, not an exception.
 
**Database as source of truth** — All node states, scaling events, and metrics live in PostgreSQL. ScaleForge can restart at any time and fully recover its operational state before resuming decisions.
 
 
## Project Status
 
> **Tested Locally**
