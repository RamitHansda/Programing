# ECS vs EKS — Skydo Deployment Decision

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
