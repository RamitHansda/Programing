# ECS vs EKS — Skydo Deployment Decision

## How ECS Works

ECS is AWS's fully managed container orchestration service. It handles scheduling, placement, health checks, scaling, and networking for containers — without you managing a control plane.

### Core Building Blocks

```
┌─────────────────────────────────────────────────────────┐
│                      ECS Cluster                        │
│                                                         │
│   ┌─────────────────┐       ┌─────────────────┐        │
│   │    Service A     │       │    Service B     │        │
│   │  (payments-api)  │       │  (reconciler)    │        │
│   │                 │       │                 │        │
│   │  Task  │  Task  │       │  Task  │  Task  │        │
│   └─────────────────┘       └─────────────────┘        │
│                                                         │
│          Fargate (serverless compute)                   │
└─────────────────────────────────────────────────────────┘
```

**Cluster**
The logical boundary for your services and tasks. One cluster can contain multiple services. In Skydo's case, you likely have a single cluster (or separate ones per environment — staging, production) grouping all microservices.

**Task Definition**
The blueprint for a container — equivalent to a `docker-compose` spec but for AWS. It defines:
- Docker image (pulled from ECR)
- CPU and memory allocation
- Environment variables and secrets (from Secrets Manager / Parameter Store)
- IAM task role (what AWS services this container can call)
- Log configuration (CloudWatch Logs)
- Port mappings and networking mode

```json
{
  "family": "payments-api",
  "cpu": "512",
  "memory": "1024",
  "taskRoleArn": "arn:aws:iam::ACCOUNT:role/payments-api-task-role",
  "containerDefinitions": [{
    "image": "ACCOUNT.dkr.ecr.ap-south-1.amazonaws.com/payments-api:v1.2.3",
    "secrets": [{ "name": "DB_PASSWORD", "valueFrom": "arn:aws:secretsmanager:..." }],
    "logConfiguration": { "logDriver": "awslogs", "options": { "awslogs-group": "/ecs/payments-api" }}
  }]
}
```

**Task**
A running instance of a Task Definition — one or more containers running together. Tasks are ephemeral. If a task dies, ECS reschedules it automatically.

**Service**
A Service keeps N tasks of a given Task Definition running at all times. It handles:
- Desired count maintenance (restarts failed tasks)
- Rolling or blue/green deployments
- Integration with ALB (registers/deregisters tasks as targets)
- Auto Scaling based on CPU, memory, or custom CloudWatch metrics

---

### Launch Types — EC2 vs Fargate

**EC2 Launch Type**
- You provision and manage EC2 instances that form the cluster.
- ECS agent runs on each EC2 instance and communicates with the control plane.
- You control instance type, patching, capacity.
- More control, more operational burden.

**Fargate Launch Type (what Skydo uses)**
- No EC2 instances to manage. AWS provisions compute on-demand per task.
- You define CPU/memory in the task definition. AWS figures out where to run it.
- Per-second billing for actual task runtime.
- Fargate Spot — up to 70% cheaper, AWS can interrupt with 2-minute notice. Ideal for reconciliation jobs, async workers.

---

### Networking in ECS

ECS with Fargate uses `awsvpc` networking mode — each task gets its own Elastic Network Interface (ENI) with a private IP inside your VPC.

```
Internet
    │
    ▼
Application Load Balancer (public subnet)
    │
    ▼  (target group — routes to task IPs)
ECS Tasks (private subnet, each with own ENI)
    │
    ▼
RDS / ElastiCache / Internal Services (private subnet)
```

- Security Groups are applied at the **task level** (not instance level), so each service has its own ingress/egress rules.
- Tasks communicate with each other via internal ALBs, AWS Cloud Map (service discovery), or directly by private IP.
- Outbound internet access goes through a NAT Gateway in a public subnet.

---

### How a Deployment Works

When you push a new image and update the ECS service:

1. **New Task Definition revision** is registered with the updated image tag.
2. ECS service detects the new revision and begins a **rolling deployment**.
3. ECS launches new tasks with the new image alongside existing tasks.
4. ALB health checks run against new tasks. Once healthy, ALB starts routing traffic to them.
5. Old tasks are drained (in-flight requests complete) then terminated.
6. Deployment completes when all tasks are running the new revision.

For **blue/green deploys** (CodeDeploy + ECS):
- A second target group is created (green environment).
- Traffic is shifted in configurable increments (e.g., canary 10% → 100%).
- Full rollback is instant — just switch the listener back to the blue target group.

---

### Auto Scaling in ECS

ECS services integrate with **Application Auto Scaling** to scale task count. The right metric depends entirely on the type of workload. At Skydo, there are three distinct workload categories:

---

#### Category 1 — Payment API (Synchronous, Latency-Sensitive)

These are the customer-facing APIs handling international payment initiation, FX rate queries, and status checks. Latency is a first-class concern — a degraded payment API is a direct user-facing failure.

**Primary metric: ALB Request Count per Target**
- The most direct signal for a synchronous API.
- Each task has a known throughput capacity before latency degrades (e.g., 400–500 RPS/task at acceptable p99 latency).
- Scale out when the rolling average crosses 70–80% of that capacity.
- This gives ECS time to launch new tasks and register them with the ALB *before* tasks are overwhelmed.

**Secondary metric: CPU Utilization**
- Payment processing is CPU-bound — TLS termination, JWT validation, DB queries, external gateway calls, encryption via KMS all consume CPU.
- Scale out at 65–70% average CPU across the service.
- Acts as a safety net when request rate alone doesn't capture compute pressure (e.g., expensive reconciliation queries on the same service).

**Do NOT use memory here.** Memory is slow-moving and a lagging indicator — by the time memory pressure is visible, the service is already degraded.

**Scale-in must be conservative.** Use a long cooldown (300–600 seconds). Payment APIs should never scale in aggressively during active traffic — the cost of terminating a task mid-transaction far exceeds the cost of a few extra running tasks.

```
Scale-out trigger:  ALB RPS/task > 400  OR  avg CPU > 70%
Scale-in trigger:   ALB RPS/task < 150 AND avg CPU < 30%  (sustained 10 min)
Min tasks:          2  (always maintain redundancy across AZs)
Max tasks:          20 (hard cap to protect downstream DB connection pool)
```

---

#### Category 2 — Reconciliation & Onboarding Workers (Async, SQS-Driven)

These are background workers consuming from SQS queues — settlement reconciliation, onboarding document processing, debit instruction generation. They are not latency-sensitive but have SLA windows (e.g., reconciliation must complete before EOD).

**Primary metric: SQS `ApproximateNumberOfMessagesVisible`**
- Queue depth is the direct measure of backlog.
- Scale out when messages accumulate beyond what current tasks can drain within the SLA window.
- Example: if each task processes 10 messages/min and your SLA requires draining within 30 minutes, scale out when queue depth > (tasks × 10 × 30).

**Secondary metric: SQS `ApproximateAgeOfOldestMessage`**
- This is the most important SLA-protection metric.
- If the oldest message in the queue is aging (e.g., >5 minutes for onboarding, >15 minutes for reconciliation), scale out aggressively regardless of queue depth.
- A high message age means workers are falling behind — this is a direct leading indicator of an SLA breach on settlement timelines.

**Scale-in is aggressive here.** Workers are stateless. Once the queue drains, scale in quickly to zero (or minimum 1) to save cost. These are ideal candidates for **Fargate Spot** — if a spot task is interrupted, the SQS message becomes visible again after the visibility timeout and another task picks it up.

```
Scale-out trigger:  QueueDepth > 500  OR  OldestMessageAge > 5 min
Scale-in trigger:   QueueDepth < 10 (sustained 5 min)
Min tasks:          0 (scale to zero during off-hours)
Max tasks:          50 (bounded by downstream DB write throughput)
Launch type:        Fargate Spot (interruptible — SQS handles retries)
```

---

#### Category 3 — Scheduled / EventBridge-Triggered Jobs

These are one-off ECS task runs triggered by EventBridge rules or Lambda (e.g., daily FX rate refresh, EOD settlement batch, compliance report generation). They are not long-running services, so traditional auto scaling does not apply.

**Concurrency is controlled at the trigger level:**
- EventBridge schedules the task run directly — ECS launches a task, it runs to completion, terminates.
- For fan-out patterns (e.g., process N entities in parallel), use Lambda to split work and trigger multiple ECS tasks concurrently, bounded by a configurable max concurrency parameter.
- No ALB, no desired count, no scaling policy needed.

---

#### Auto Scaling Metric Summary

| Service Type | Primary Metric | Secondary Metric | Scale-in Strategy | Fargate Spot? |
|---|---|---|---|---|
| Payment API | ALB RPS per target | CPU utilization | Conservative (10 min cooldown) | No — on-demand only |
| Reconciliation worker | SQS queue depth | Oldest message age | Aggressive (scale to 0) | Yes |
| Onboarding worker | SQS queue depth | Oldest message age | Aggressive (scale to 0) | Yes |
| Scheduled jobs | N/A (EventBridge controlled) | N/A | N/A — task runs to completion | Optional |

Scale-in cooldown prevents tasks from being terminated too aggressively after a traffic spike. For payment APIs, always prefer over-provisioning to under-provisioning — the cost asymmetry heavily favors availability.

---

### IAM and Security Model

Each ECS task has two IAM roles:

| Role | Purpose |
|---|---|
| **Task Execution Role** | Allows ECS agent to pull images from ECR, write logs to CloudWatch, fetch secrets from Secrets Manager |
| **Task Role** | The permissions your application code uses at runtime — e.g., publish to SQS, read from S3, call KMS |

This is the equivalent of Kubernetes IRSA (IAM Roles for Service Accounts) but simpler — no service account annotations or OIDC provider setup needed.

---

### ECS + Skydo's Stack — How It All Connects

```
GitHub Actions (CI)
    │
    ▼
Build Docker image → Push to ECR
    │
    ▼
Register new Task Definition revision
    │
    ▼
Update ECS Service → Rolling deploy
    │
    ├── ALB → routes HTTPS traffic to tasks
    ├── Secrets Manager → DB credentials, API keys injected at task start
    ├── KMS → envelope encryption for secrets at rest
    ├── CloudWatch Logs → all container stdout/stderr
    ├── SQS → async workers (reconciliation, onboarding) pull from queues
    ├── EventBridge → scheduled jobs trigger Lambda or ECS tasks
    └── VPC Security Groups → control task-level network access
```

---

## Context

Skydo is a fintech platform processing 10K+ international transactions/day, run by a team of 12 engineers. The AWS stack includes ECS, ECR, SQS, SNS, EventBridge, Lambda, KMS, and VPC. The platform holds ISO 27001 and SOC 2 Type II certifications.

---

## Why ECS Made Sense for Skydo

### 1. Operational Simplicity at Scale

- No control plane to manage — AWS owns the scheduler, health checks, and cluster management.
- With 12 engineers, no one needs to be a Kubernetes expert to deploy, debug, or scale a service.
- ECS Fargate removes the need to manage EC2 instances — define CPU/memory per task and AWS handles placement.

### 2. Deep Native AWS Integration

- The full stack (SQS, SNS, EventBridge, Lambda, KMS, VPC, ECR) integrates with ECS with near-zero glue code.
- IAM task roles work seamlessly — each service gets fine-grained AWS permissions without sidecars or mutating webhooks.
- ALB target group registration, service discovery via AWS Cloud Map, and secrets via Secrets Manager/Parameter Store all work out of the box.

### 3. Compliance Alignment (ISO 27001 / SOC 2)

- VPC-native networking, KMS-managed secrets, CloudTrail audit logs, and IAM policies form a well-understood security boundary.
- Auditors are familiar with these native AWS primitives — no need to explain custom CNI plugins, RBAC controllers, or etcd encryption during a SOC 2 audit.

### 4. Cost Efficiency for Predictable Workloads

- Fargate Spot for non-critical async workers (reconciliation jobs, onboarding flows).
- Fargate on-demand for payment-critical services.
- No idle node overhead — pay per task CPU/memory, not per underutilized EC2 instance.

### 5. Deployment Velocity

- ECS rolling deploys with CodePipeline/GitHub Actions are straightforward to configure.
- Blue/green deploys via CodeDeploy + ECS require minimal setup compared to Kubernetes rolling strategies, Argo Rollouts, or Flagger.

---

## ECS — Real Cons

### 1. No Native Multi-Cluster Federation

- ECS has no native way to federate clusters across regions. Multi-region active-active requires manual stitching via Route 53, Global Accelerator, and custom tooling.

### 2. Limited Scheduling Sophistication

- ECS task placement strategies (binpack, spread, random) are far less expressive than Kubernetes node affinity, pod topology spread constraints, or custom schedulers.
- GPU workloads, ML inference, or co-location requirements become awkward.

### 3. No CNCF Ecosystem Leverage

- Tools like Argo Workflows, KEDA, Istio, and the OpenTelemetry Operator do not work with ECS.
- Event-driven autoscaling tied to SQS queue depth requires custom CloudWatch alarms + Application Auto Scaling — functional but verbose. KEDA on EKS does this declaratively in ~10 lines of YAML.

### 4. Observability Gaps

- No native distributed tracing sidecar injection. Relies on in-app instrumentation or manual sidecar setup in task definitions.
- AWS App Mesh (Envoy) exists but is significantly less mature and less adopted than Istio or Linkerd on Kubernetes.

### 5. Zero Portability

- ECS is 100% AWS-proprietary. Task definitions, service configs, and deployment tooling have no portability to GCP, Azure, or on-prem.

---

## Why Not EKS at Skydo's Stage

| Factor | ECS | EKS |
|---|---|---|
| Team size (12 engineers) | Right fit | Needs a dedicated infra/platform team |
| Kubernetes expertise required | None | High — CRDs, RBAC, CNI, etcd, upgrades |
| Control plane cost | Free (Fargate) | ~$73/month/cluster + node costs |
| AWS integration | Native | Needs glue (IRSA, VPC CNI, ALB Ingress Controller) |
| Compliance auditability | Simple IAM/VPC model | Larger surface area to audit |
| Time to first production deploy | Hours | Days to weeks for a production-grade setup |
| Operational overhead | Low | High — upgrades, node groups, add-on lifecycle |

---

## When EKS Becomes the Right Choice

Consider migrating to EKS when Skydo hits these triggers:

- A **dedicated platform/SRE team** (3+ engineers) manages infra full-time.
- **Multi-cloud portability** or hybrid cloud is required.
- **Complex stateful workloads** (ML pipelines, databases) need advanced scheduling.
- CNCF tooling (Argo CD, KEDA, Istio) is needed at scale.
- Microservices count crosses **50+ services** requiring a full service mesh with fine-grained traffic policies.

---

## Summary

ECS was the correct architectural decision for Skydo at this stage. The team is focused on product velocity across payments, onboarding, and reconciliation — not infrastructure abstractions. ECS delivers **90% of what's needed at 20% of the operational cost** of EKS.

The natural migration point to evaluate EKS is when Skydo scales to a dedicated infra team, requires multi-region active-active deployments, or begins running ML workloads at significant scale.
