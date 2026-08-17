#!/usr/bin/env python3
"""Generate Staff Engineer Docker interview Q&A PDF."""

from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY, TA_LEFT
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.platypus import (
    KeepTogether,
    ListFlowable,
    ListItem,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

OUT = Path(__file__).with_name("DOCKER_STAFF_ENGINEER_INTERVIEW_QA.pdf")


def styles():
    base = getSampleStyleSheet()
    return {
        "cover_title": ParagraphStyle(
            "cover_title",
            parent=base["Title"],
            fontName="Helvetica-Bold",
            fontSize=26,
            leading=32,
            alignment=TA_CENTER,
            textColor=colors.HexColor("#0f172a"),
            spaceAfter=12,
        ),
        "cover_sub": ParagraphStyle(
            "cover_sub",
            parent=base["Normal"],
            fontName="Helvetica",
            fontSize=12,
            leading=16,
            alignment=TA_CENTER,
            textColor=colors.HexColor("#334155"),
            spaceAfter=8,
        ),
        "section": ParagraphStyle(
            "section",
            parent=base["Heading1"],
            fontName="Helvetica-Bold",
            fontSize=16,
            leading=20,
            textColor=colors.HexColor("#0f172a"),
            spaceBefore=18,
            spaceAfter=10,
            borderPadding=4,
        ),
        "q": ParagraphStyle(
            "q",
            parent=base["Heading2"],
            fontName="Helvetica-Bold",
            fontSize=11,
            leading=14,
            textColor=colors.HexColor("#0b3d5c"),
            spaceBefore=12,
            spaceAfter=6,
        ),
        "body": ParagraphStyle(
            "body",
            parent=base["Normal"],
            fontName="Helvetica",
            fontSize=9.5,
            leading=13,
            alignment=TA_JUSTIFY,
            textColor=colors.HexColor("#1e293b"),
            spaceAfter=4,
        ),
        "label": ParagraphStyle(
            "label",
            parent=base["Normal"],
            fontName="Helvetica-Bold",
            fontSize=9,
            leading=12,
            textColor=colors.HexColor("#0f766e"),
            spaceBefore=4,
            spaceAfter=2,
        ),
        "bullet": ParagraphStyle(
            "bullet",
            parent=base["Normal"],
            fontName="Helvetica",
            fontSize=9.5,
            leading=12.5,
            textColor=colors.HexColor("#1e293b"),
            leftIndent=8,
        ),
        "footer": ParagraphStyle(
            "footer",
            parent=base["Normal"],
            fontName="Helvetica",
            fontSize=8,
            textColor=colors.HexColor("#64748b"),
            alignment=TA_CENTER,
        ),
        "callout": ParagraphStyle(
            "callout",
            parent=base["Normal"],
            fontName="Helvetica-Oblique",
            fontSize=9.5,
            leading=13,
            textColor=colors.HexColor("#334155"),
            alignment=TA_LEFT,
            spaceBefore=4,
            spaceAfter=8,
        ),
        "toc": ParagraphStyle(
            "toc",
            parent=base["Normal"],
            fontName="Helvetica",
            fontSize=10,
            leading=15,
            textColor=colors.HexColor("#1e293b"),
            leftIndent=12,
        ),
    }


QA = [
    (
        "Architecture & Runtime Internals",
        [
            (
                "1. How do namespaces and cgroups create isolation, and where does that model fail?",
                [
                    "Staff framing: Docker does not invent isolation — it composes Linux kernel primitives. Namespaces partition visibility (PID, mount, net, UTS, IPC, user). Cgroups enforce consumption (CPU, memory, I/O, PIDs).",
                    "What a strong answer includes: namespaces hide; cgroups throttle; the kernel, page cache, and many kernel subsystems remain shared. That shared kernel is the primary trust boundary.",
                    "Failure modes Staff must call out: kernel CVEs / container escapes; unbounded shared resources (disk I/O, page cache, conntrack, inode locks); privileged mode and host mounts; missing or wrong limits causing noisy neighbors; assuming 'isolated' equals VM isolation.",
                    "Decision signal: use containers for density and delivery speed among trusted workloads; escalate isolation (Kata/gVisor/VM pools) when tenants are hostile or blast radius is unacceptable.",
                ],
            ),
            (
                "2. Walk the runtime path: CLI → dockerd → containerd → runc → kernel.",
                [
                    "docker CLI is UX/API client. dockerd owns Docker-specific orchestration: image UX, networks, volumes, compatibility APIs.",
                    "Lifecycle execution is largely delegated to containerd (pull/unpack, content store, task management). containerd invokes an OCI runtime — typically runc — to create the sandbox.",
                    "runc applies namespaces/cgroups/seccomp/capabilities and execs the process into that environment via kernel syscalls. The kernel is the real enforcer.",
                    "Staff implication: design platforms against OCI + CRI/containerd contracts, not Docker Engine sock semantics. Coupling CI/CD or prod to /var/run/docker.sock is a strategic liability.",
                ],
            ),
            (
                "3. Why did the industry standardize on OCI, containerd, and CRI-O?",
                [
                    "Docker Engine historically coupled developer UX, daemon, and runtime. Orchestrators needed a stable, pluggable runtime interface without depending on dockerd.",
                    "OCI standardized image and runtime specs; containerd/CRI-O became kubelet-facing runtimes. This enables runtime swaps (runc/Kata) without rewriting app packaging.",
                    "Staff recommendation: keep Docker where it accelerates local DX if needed, but standardize artifacts (OCI images by digest) and runtime policy on what production actually runs.",
                    "Anti-pattern: app teams baking Docker-only APIs, DinD assumptions, or Engine-specific networking into production control planes.",
                ],
            ),
            (
                "4. Compare overlayfs, volumes, and bind mounts for production risk.",
                [
                    "Overlay writable layer: correct for ephemeral FS; poor default for write-heavy state due to copy-up amplification, metadata overhead, and data loss on container remove.",
                    "Volumes: platform-managed persistence with clearer lifecycle; default for durable container data when local disk is acceptable.",
                    "Bind mounts: max flexibility, max host coupling — useful in controlled node agents/dev, dangerous as a general prod pattern (permissions, path escapes, host drift).",
                    "Staff tradeoff: optimize for operability and failure modes, not microbenchmarks. Stateful systems usually belong on explicit storage contracts (CSI/managed DB), not container CoW layers.",
                ],
            ),
            (
                "5. How does Docker networking work, and when do you reject the default bridge?",
                [
                    "Default bridge provides basic NATed connectivity but weak service discovery, awkward policy, and operational surprises at scale.",
                    "User-defined bridges improve DNS/service discovery for compose-style apps. Host networking maximizes performance and removes NAT — at the cost of network namespace isolation.",
                    "In orchestrators, prefer CNI + network policy over Docker-native networking abstractions.",
                    "Reject default bridge for anything beyond local experiments: it optimizes for convenience, not multi-service operability or tenancy controls.",
                ],
            ),
            (
                "6. Design multi-tenant containers on shared hosts without pretending you have VMs.",
                [
                    "Start from threat model: trusted internal services vs untrusted tenants. Soft multi-tenancy (policy) vs hard multi-tenancy (VM-level) are different products.",
                    "Controls: admission deny for privileged/hostPath/docker.sock; non-root + dropped caps + seccomp + MAC; per-tenant CPU/mem/PID/IOPS quotas; network policy; signed images; short-lived secrets.",
                    "Segmentation: sensitive workloads on dedicated node pools or Kata/Firecracker; default pool for hardened trusted services.",
                    "Staff outcome: publish residual risk explicitly. Shared-kernel tenancy is a risk-acceptance decision with compensating controls, not a checkbox.",
                ],
            ),
            (
                "7. How do you reason about noisy neighbors across CPU, memory, disk, and network?",
                [
                    "CPU: CFS quota/throttle creates latency cliffs before hard failure. Memory: reclaim + cgroup OOM can look like random app crashes. Disk: unchecked writes saturate shared devices and overlay. Network: bandwidth, pps, and conntrack exhaustion.",
                    "Instrument saturation: throttling, memory pressure, IO wait, discards/reclaims, SYN/conntrack drops — not only app RED metrics.",
                    "Mitigations: explicit limits, QoS classes, I/O controls, node classes by criticality, density caps, and admission based on real utilization.",
                    "Staff point: packing density is an SRE/finance tradeoff with measurable blast radius; treat it as capacity architecture.",
                ],
            ),
            (
                "8. How can image layering and page cache create unexpected production pressure?",
                [
                    "CoW layers make image reuse cheap, but large images create pull latency and cold-start I/O storms. Writing through layered files triggers copy-up amplification.",
                    "Many containers from one image can share host page cache (good), yet scanners/unpackers and huge bases can evict useful cache and amplify node memory pressure.",
                    "Staff actions: slim/golden bases, multi-stage builds, reduce layer churn, digest-pinned deploys, registry caches near clusters, and treat image design as performance engineering.",
                ],
            ),
        ],
    ),
    (
        "Isolation, Security & Trust Boundaries",
        [
            (
                "9. Does Docker provide true isolation? Defend the answer.",
                [
                    "No — not hypervisor-true isolation. It provides strong logical isolation for trusted workloads: separate process/filesystem/network views and enforceable resource limits.",
                    "The kernel remains shared, so kernel exploits, side channels, and misconfigured privileges remain in the threat model.",
                    "Staff communication: containers optimize packaging density and delivery; isolation strength is a function of runtime policy + kernel posture + tenancy model, not the Dockerfile.",
                ],
            ),
            (
                "10. Walk through container escape risk and blast-radius controls.",
                [
                    "Typical path: privileged container, docker.sock mount, host PID/net/mount namespaces, overly broad capabilities, or kernel vulnerability chained with write access to host resources.",
                    "Reduce likelihood: non-root, read-only rootfs, drop capabilities, strict seccomp, MAC, patched kernels, minimal images, admission webhooks.",
                    "Reduce blast radius: dedicated pools, network policy, least-privilege cloud IAM, short-lived credentials, runtime detection, rapid revoke paths.",
                    "Staff mindset: prevent first, contain second, detect always. Exceptions are time-boxed and audited.",
                ],
            ),
            (
                "11. Root in a container vs root on the host; role of user namespaces.",
                [
                    "Without user namespaces, container UID 0 maps to host UID 0 inside a constrained view — still dangerous with mounts/capabilities.",
                    "User namespaces remap container root to an unprivileged host UID, shrinking escape impact. Necessary but not sufficient.",
                    "Staff policy: combine user namespaces (where viable) with non-root images, capability drops, and deny-by-default admission. Never equate 'namespaced root' with safe.",
                ],
            ),
            (
                "12. When do you choose gVisor, Kata, Firecracker, or full VMs over runc?",
                [
                    "runc: highest density/perf for trusted code. gVisor: stronger syscall interception with compatibility/perf tradeoffs. Kata/Firecracker: lightweight VMs for stronger isolation of untrusted workloads. Full VMs: strongest classic boundary, lowest density.",
                    "Choose by adversary model and blast radius, not fashion. Untrusted plugins, customer code, or hostile multi-tenant CI builders justify stronger isolation.",
                    "Staff move: tier workloads. Don't pay VM tax everywhere; don't run hostile tenants on vanilla runc 'because Kubernetes'.",
                ],
            ),
            (
                "13. What does a production container hardening standard look like?",
                [
                    "Mandatory: non-root, no privileged, resource limits, digest pins, signed+scanned images, approved bases, secrets policy, no host NS/docker.sock.",
                    "Strongly preferred: read-only rootfs, drop-all caps + additive allowlist, seccomp, AppArmor/SELinux, distroless/minimal images.",
                    "Enforcement: CI gates + cluster admission + exception tickets with expiry. Standards without enforcement are blog posts.",
                ],
            ),
            (
                "14. How should secrets be injected? Critique common options.",
                [
                    "Env vars: convenient and leaky (proc dumps, logs, crash reports, child inheritance) — avoid for long-lived secrets.",
                    "Bind-mounted files: better delivery shape, still sensitive to host FS perms and image debugging practices.",
                    "Orchestrator secrets: better lifecycle/RBAC, still require encryption at rest and tight access control.",
                    "External manager (Vault/cloud SM): best for rotation, audit, short-lived credentials. Staff default: file-mounted short-lived secrets, never baked into images, never in build logs.",
                ],
            ),
            (
                "15. Policy for privileged containers, host namespaces, and docker.sock mounts?",
                [
                    "Treat as node root-equivalent. Default deny in admission. Most 'temporary debug' requests are permanent risk.",
                    "If unavoidable (rare node agents): dedicated tainted nodes, narrow hostPath, audited break-glass, time-bounded exceptions, prefer purpose-built alternatives over sock mounts.",
                    "Staff response in review: block merge, explain blast radius in business terms, offer a paved safer path.",
                ],
            ),
        ],
    ),
    (
        "Production Systems & Reliability",
        [
            (
                "16. Design an image build and promotion pipeline for a large org.",
                [
                    "Build once in CI → SBOM + scan + sign → promote immutable digest across environments → deploy by digest → canary → automated rollback.",
                    "Separate build identity from runtime identity. Mutable tags are coordination hazards; digests are contracts.",
                    "Staff concerns: provenance, who can publish prod digests, exception workflow for CVE waivers, and rebuild fan-out after base CVE patches.",
                ],
            ),
            (
                "17. How do you manage base-image sprawl and CVE remediation at scale?",
                [
                    "Golden bases with clear owners, automated rebuilds, SBOM-driven dependency graphs, severity SLAs, and progressive rollout of base bumps.",
                    "Kill unmanaged bases. Prefer minimal/distroless to shrink CVE surface and rebuild cost.",
                    "Staff metric: time-to-remediate critical base CVEs across dependent services, not number of scanners enabled.",
                ],
            ),
            (
                "18. Containers are OOMing intermittently. How do you investigate?",
                [
                    "Disambiguate app leak vs undersized limit vs bursty working set vs neighbor-induced pressure vs cgroup v1/v2 semantics vs host OOM vs cgroup OOM.",
                    "Evidence: container memory working set/RSS/cache, OOM killer logs, heap profiles, throttle/pressure metrics, correlated noisy neighbors.",
                    "Staff trap: raising limits blindly. Fix the class of failure and encode limits from measured p95/p99 + headroom.",
                ],
            ),
            (
                "19. How do you set CPU/memory limits without creating silent latency disasters?",
                [
                    "Derive from load tests and production percentiles, not guesses. Track CPU throttling explicitly — throttled services fail as latency, not crashes.",
                    "Latency-sensitive paths need headroom; batch can run hotter. In Kubernetes, understand requests (scheduling guarantee) vs limits (cap).",
                    "Staff practice: capacity envelopes per service tier, with alerts on throttle/pressure before customer SLOs burn.",
                ],
            ),
            (
                "20. How do you debug intermittent DNS failures between containers?",
                [
                    "Map resolver path (embedded DNS, node resolvers, cluster DNS), then check conntrack exhaustion, packet loss, network policy, DNS backend overload, ndots/search domains, and dual-stack behavior.",
                    "Correlate app retries with node networking metrics. Distinguish client timeout policy bugs from true resolution failures.",
                    "Staff fix often includes both infra saturation controls and client resilience (timeouts, caching, retry budgets).",
                ],
            ),
            (
                "21. What observability is required for containers, and where do agents belong?",
                [
                    "Need logs with correlation IDs, RED/USE metrics, traces, and runtime/cgroup signals (throttle, OOM, pressure). App metrics alone hide runtime failure modes.",
                    "Prefer carefully designed node-level agents over privileged sidecars on every pod. Avoid turning observability into the largest attack surface.",
                    "Staff bar: can you explain a deploy-induced latency spike using runtime saturation evidence in minutes?",
                ],
            ),
            (
                "22. How do you get graceful shutdown right (PID 1, signals, init)?",
                [
                    "PID 1 must handle signals and reap zombies. Use correct STOPSIGNAL/ENTRYPOINT and often a minimal init (tini) when the app is a poor PID 1.",
                    "Apps must drain on SIGTERM within termination grace; orchestrators must give enough time and not route new traffic after preStop/drain.",
                    "Staff view: shutdown is part of the availability design. Broken signal handling becomes deploy-time packet loss.",
                ],
            ),
        ],
    ),
    (
        "Platform Strategy & Organizational Leverage",
        [
            (
                "23. Standardize on Docker Engine/Desktop or move to Podman/nerdctl/K8s-native workflows?",
                [
                    "Optimize for developer velocity and production parity. If production is Kubernetes/containerd, reduce Engine-specific coupling over time.",
                    "Standardize on OCI images and deployment manifests. Keep Docker where local UX wins, but don't let Desktop convenience dictate prod architecture.",
                    "Staff decision criteria: cognitive load, security boundary of the daemon, CI compatibility, and migration cost — not tool tribalism.",
                ],
            ),
            (
                "24. Propose a container security standard and enforcement model.",
                [
                    "Mandatory vs advisory controls (see Q13). Enforcement in CI + admission control + continuous runtime policy.",
                    "Exception process with owner, risk acceptance, expiry, and metrics on exception age/count.",
                    "Staff leverage: paved-road templates so the secure path is the easiest path. Culture plus gates beats either alone.",
                ],
            ),
            (
                "25. How do you choose packing density (containers per host)?",
                [
                    "Density improves unit economics and worsens blast radius/noisy-neighbor probability. Segment by criticality.",
                    "Use saturation metrics (throttle, mem pressure, disk latency) and failure domain size, not average CPU.",
                    "Staff recommendation: tiered pools — regulated/high-sensitivity low density; internal hardened medium; batch high density.",
                ],
            ),
            (
                "26. Design local-dev parity without destroying developer laptops.",
                [
                    "Same image digests where feasible; compose for local; stub expensive deps; push heavy stacks to remote ephemeral environments.",
                    "Do not require full prod security/perf topology on every laptop. Parity is about contracts and failure shapes, not cloning the data center.",
                    "Staff outcome: reduce 'works on my machine' by sharing artifacts and contracts, not by forcing identical runtime topology.",
                ],
            ),
            (
                "27. Migrate a large monolith to containers with controlled risk.",
                [
                    "Lift-and-shift process boundaries first; externalize config/state; add health/readiness; then split seams. Avoid rewrite + containerize in one leap.",
                    "Use canary/blue-green, feature flags, and instant rollback to prior host/VM path.",
                    "Staff sequencing: operability first (signals, deploys, observability), then decomposition. Containers are a packaging/runtime move, not an architecture miracle.",
                ],
            ),
            (
                "28. When are stateful containers acceptable, and when are they a bad idea?",
                [
                    "Acceptable with explicit storage contract, backup/restore, failover story, and operators/runbooks. Bad when teams want cattle ops but run pet databases on local disk.",
                    "Default to managed data services unless platform economics/latency requirements justify operating state yourself.",
                    "Staff question: who owns durability, recovery time, and corruption risk when the node dies at 2am?",
                ],
            ),
            (
                "29. DinD vs Kaniko vs BuildKit vs build VMs for CI?",
                [
                    "DinD: convenient and often privileged/sock-based — weak isolation. Kaniko: daemonless builds in-cluster. BuildKit: fast caching and modern frontend features. Build VMs: strongest isolation for untrusted builds.",
                    "Multi-tenant/untrusted CI → VM builders. Trusted mono-repo → BuildKit/Kaniko with locked identity and cache isolation.",
                    "Staff criterion: build systems are part of the supply-chain trust boundary, not a DX footnote.",
                ],
            ),
            (
                "30. How do you stop Docker/Kubernetes complexity from becoming an org bottleneck?",
                [
                    "Provide paved roads: golden service templates, secure defaults, CI libraries, self-service with guardrails, and narrow escape hatches.",
                    "Measure platform SLIs (time to scaffold, deploy success, MTTR, policy exception lag). Staff success is leverage, not ticket heroics.",
                    "Raise the baseline while reducing cognitive load. If every team must become a runtime expert, the platform has failed.",
                ],
            ),
        ],
    ),
    (
        "Scenario Prompts (Staff Judgment)",
        [
            (
                "31. Incident: base-image bump breaks 20% of prod health checks.",
                [
                    "Contain: pause rollout / pin previous digest if blast radius is large. Compare failed vs healthy by digest, entrypoint, glibc/cert/timezone deps, probe assumptions, and UID changes from slimmer bases.",
                    "Often probes depended on shells/tools removed by distroless/minimal moves. Contract-test health endpoints against golden bases going forward.",
                    "Staff follow-through: progressive base canaries, ownership of golden images, and a rollback path that doesn't require app code changes.",
                ],
            ),
            (
                "32. Design a secure multi-team shared-node platform.",
                [
                    "Namespaces + RBAC + network policies + quotas + pod security admission for soft tenancy. Hard isolation pools for high-risk workloads.",
                    "Central image policy, digest deploys, audited break-glass, cost attribution per team.",
                    "Staff deliverable: a written tenancy model with explicit guarantees and non-guarantees.",
                ],
            ),
            (
                "33. Finance wants 3x density; Security wants stronger isolation. Decide.",
                [
                    "Refuse a single global setting. Tier the fleet: regulated/sensitive on stronger isolation/dedicated nodes; internal hardened on moderate density; batch on high density.",
                    "Quantify residual risk and cost delta. Get explicit risk acceptance for each tier.",
                    "Staff behavior: negotiate with data and blast-radius framing, not tool absolutism.",
                ],
            ),
            (
                "34. A team mounts /var/run/docker.sock into a debug sidecar 'temporarily'.",
                [
                    "Block it. Sock access is effectively root on the node and often the cluster build/runtime control plane.",
                    "Offer alternatives: authenticated API proxy, central build service, kubectl debug, or dedicated break-glass node agent with expiry.",
                    "If emergency: tainted dedicated node, ticketed exception with owner/expiry/audit — never 'temporary' on shared general pools.",
                ],
            ),
            (
                "35. Image pulls dominate p95 deploy latency. Cut it without weakening supply chain.",
                [
                    "Shrink images, reduce layer churn, regional registry mirrors/caches, warm node caches, parallel pull tuning, deploy by already-present digests where possible.",
                    "Preserve signing/admission. Lazy pull only where supported and verified.",
                    "Staff framing: deploy latency is a supply-chain + capacity problem; don't trade verification away for speed.",
                ],
            ),
        ],
    ),
]


CLOSING = (
    "Containers package and isolate processes efficiently by sharing a kernel. "
    "That makes them excellent for density and delivery speed, but isolation is only as strong as "
    "runtime policy, kernel posture, and tenancy design. Staff engineers design platforms and controls "
    "around that trust boundary — not around Docker CLI features."
)


def bullets(items, s):
    flow = []
    for item in items:
        flow.append(ListItem(Paragraph(item, s["bullet"]), leftIndent=12, bulletColor=colors.HexColor("#0f766e")))
    return ListFlowable(
        flow,
        bulletType="bullet",
        start="•",
        leftIndent=15,
        bulletFontSize=8,
        spaceBefore=0,
        spaceAfter=6,
    )


def add_header_footer(canvas, doc):
    canvas.saveState()
    canvas.setStrokeColor(colors.HexColor("#cbd5e1"))
    canvas.setLineWidth(0.5)
    canvas.line(0.75 * inch, letter[1] - 0.55 * inch, letter[0] - 0.75 * inch, letter[1] - 0.55 * inch)
    canvas.setFont("Helvetica", 8)
    canvas.setFillColor(colors.HexColor("#64748b"))
    canvas.drawString(0.75 * inch, letter[1] - 0.45 * inch, "Docker — Staff Engineer Interview Q&A")
    canvas.drawRightString(letter[0] - 0.75 * inch, letter[1] - 0.45 * inch, "Staff / Principal bar")
    canvas.line(0.75 * inch, 0.55 * inch, letter[0] - 0.75 * inch, 0.55 * inch)
    canvas.drawCentredString(letter[0] / 2, 0.38 * inch, f"Page {doc.page}")
    canvas.restoreState()


def build():
    s = styles()
    doc = SimpleDocTemplate(
        str(OUT),
        pagesize=letter,
        leftMargin=0.75 * inch,
        rightMargin=0.75 * inch,
        topMargin=0.75 * inch,
        bottomMargin=0.75 * inch,
        title="Docker Staff Engineer Interview Q&A",
        author="Interview Prep",
    )

    story = []

    # Cover
    story.append(Spacer(1, 1.6 * inch))
    story.append(Paragraph("Docker Interview Q&amp;A", s["cover_title"]))
    story.append(Paragraph("Staff / Principal Engineer Level", s["cover_sub"]))
    story.append(Spacer(1, 0.2 * inch))
    story.append(
        Paragraph(
            "Answers emphasize trust boundaries, failure modes, platform leverage, and tradeoffs — "
            "not SDE2 definitions.",
            s["cover_sub"],
        )
    )
    story.append(Spacer(1, 0.35 * inch))

    meta = [
        [Paragraph("<b>Audience</b>", s["body"]), Paragraph("Staff / Principal backend &amp; platform engineers", s["body"])],
        [Paragraph("<b>Focus</b>", s["body"]), Paragraph("Runtime internals, isolation, security, production, org strategy", s["body"])],
        [Paragraph("<b>How to use</b>", s["body"]), Paragraph("Speak tradeoffs first; cite blast radius and enforcement, not tool features", s["body"])],
    ]
    t = Table(meta, colWidths=[1.3 * inch, 5.2 * inch])
    t.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#f1f5f9")),
                ("BOX", (0, 0), (-1, -1), 0.75, colors.HexColor("#94a3b8")),
                ("INNERGRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#cbd5e1")),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 8),
                ("RIGHTPADDING", (0, 0), (-1, -1), 8),
                ("TOPPADDING", (0, 0), (-1, -1), 6),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
            ]
        )
    )
    story.append(t)
    story.append(Spacer(1, 0.5 * inch))
    story.append(Paragraph("<b>Staff bar reminder</b>", s["label"]))
    story.append(Paragraph(CLOSING, s["callout"]))
    story.append(PageBreak())

    # TOC
    story.append(Paragraph("Contents", s["section"]))
    for i, (section, _) in enumerate(QA, 1):
        story.append(Paragraph(f"{i}. {section}", s["toc"]))
    story.append(Paragraph("Closing one-liner interviewers reward", s["toc"]))
    story.append(PageBreak())

    # What differentiates Staff answers
    story.append(Paragraph("What Separates Staff Answers from SDE2", s["section"]))
    diffs = [
        "Lead with the trust boundary (shared kernel) and blast radius, not a feature list.",
        "Name failure modes and operational evidence you would inspect under incident pressure.",
        "Convert controls into enforceable platform policy (admission, CI gates, exceptions with expiry).",
        "Make tiered recommendations (by workload sensitivity), not one-size-fits-all dogma.",
        "Tie decisions to org leverage: paved roads, ownership, SLIs — not hero debugging.",
    ]
    story.append(bullets(diffs, s))
    story.append(Spacer(1, 0.1 * inch))

    for section, questions in QA:
        story.append(Paragraph(section, s["section"]))
        for q, points in questions:
            block = [
                Paragraph(q, s["q"]),
                Paragraph("Staff-level answer", s["label"]),
                bullets(points, s),
            ]
            story.append(KeepTogether(block))

    story.append(Paragraph("Closing Line", s["section"]))
    story.append(Paragraph(CLOSING, s["callout"]))
    story.append(Spacer(1, 0.2 * inch))
    story.append(
        Paragraph(
            "If interrupted, prioritize: shared kernel → policy/enforcement → tenancy tiers → "
            "supply chain digests → measurable saturation/blast radius.",
            s["body"],
        )
    )

    doc.build(story, onFirstPage=add_header_footer, onLaterPages=add_header_footer)
    print(f"Wrote {OUT}")


if __name__ == "__main__":
    build()
