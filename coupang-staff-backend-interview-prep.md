# Coupang Staff Backend Engineer Interview Preparation

This guide is based on the provided Coupang India Staff Engineer Backend candidate prep document and publicly reported Coupang interview experiences.

## Interview Stages

| Round | Focus |
| --- | --- |
| Round 1 | Coding Round 1 - DSA |
| Round 2 | Coding Round 2 - DSA or Low-Level Design |
| Round 3 | System Design |
| Round 4 | Focus Round - Project Deep Dive + Leadership Principles using SBI |
| Round 5 | Bar Raiser Round - Project Deep Dive + Leadership Principles using SBI |

## What Coupang Evaluates

### Coding and System Design

- Problem solving and fundamentals
- Ability to reason through ambiguous problems
- Ability to identify constraints and edge cases
- Strong computer science fundamentals
- Clear communication and structured thinking
- Clean, readable, correct, runnable code
- Sensible naming and maintainable structure
- Correct time and space complexity analysis
- Design thinking, tradeoff analysis, reliability, scalability, and operability

### Focus Round and Bar Raiser

- Leadership principles
- Ownership and impact
- Collaboration and influence
- Ability to explain project decisions clearly
- Measurable outcomes backed by data where possible
- Clear SBI-based storytelling: Situation, Behavior, Impact

## Round 1: Coding DSA

Expect medium to hard problems in a shared HackerRank-style environment with the ability to run tests.

### Topics to Prioritize

- Arrays and strings
- Hashing
- Trees
- Graphs
- Recursion
- Dynamic programming
- Stacks and queues
- Custom data structures
- Matrix/grid traversal

### Reported and Relevant Coupang Coding Questions

Prioritize these:

1. LFU Cache implementation
2. LRU Cache
3. All O(1) Data Structure / string counts with min and max
4. Minimum Stack in O(1)
5. Sort Colors / Dutch National Flag
6. Minimum connections to join friend networks
7. Number of distinct island shapes
8. Distance between two closest islands
9. Best Meeting Point
10. Largest Rectangle in Histogram
11. Decode String
12. Graph/tree identification problem
13. Design File System operations
14. Subsequence count
15. Spiral Matrix, counter-clockwise
16. Diagonal Traverse
17. Minimum Batch Size

### Coding Interview Answer Flow

Use this structure:

1. Restate the problem in your own words.
2. Clarify inputs, outputs, constraints, and edge cases.
3. Discuss a brute force approach first.
4. Improve the solution using the right data structure or algorithm.
5. Explain why the optimized approach works.
6. Code cleanly with meaningful names.
7. Dry run with an example.
8. Test edge cases.
9. State time and space complexity.

Example opening:

> Let me restate the problem. We are given X and need to return Y. I want to clarify constraints around input size, duplicates, empty input, and expected behavior for edge cases. A baseline approach is this, with this complexity. We can optimize using this data structure because it gives us this property.

### Edge Cases to Mention

- Empty input
- Single element
- Duplicate values
- Negative values if relevant
- Very large input
- Cycles in graph problems
- Disconnected components
- Integer overflow if relevant
- Invalid input if the problem allows it

## Round 2: Coding DSA or Low-Level Design

Round 2 may be another coding round or an LLD round.

### If It Is DSA

Expect another medium or hard implementation-heavy problem. High-probability areas:

- Cache implementation
- File system implementation
- Graph or tree traversal
- Custom data structure
- Matrix traversal
- Stack or queue problem

### If It Is LLD

These interviews focus on building extensible, maintainable, and testable code.

### LLD Prompts to Practice

1. Design File System
   - Create directory
   - Create file
   - Read file
   - Append content
   - List directory
2. Design LRU Cache
3. Design LFU Cache
4. Design Rate Limiter
5. Design Parking Lot
6. Design Book Shop / Online Store
7. Design Inventory Service
8. Design Notification Service

### LLD Answer Framework

1. Clarify scope early.
2. Agree on milestones.
3. Define APIs.
4. Identify entities and classes.
5. Separate responsibilities clearly.
6. Keep the design simple and extensible.
7. Implement core methods.
8. Discuss test scenarios.
9. Discuss future extensions.

Example opening:

> I will first clarify the core operations and constraints. Then I will define the main classes and APIs. I will keep the first version simple and extensible, implement the key methods, and walk through test cases.

## Round 3: System Design

This is likely the most important technical round for a Staff Backend Engineer role.

Expect high-level system design prompts where you define requirements, outline architecture, and discuss scale, storage, reliability, caching, databases, messaging, partitioning, and tradeoffs.

### Most Relevant Coupang System Design Topics

Prioritize these:

1. Design checkout / order management system
2. Design inventory management to prevent overselling
3. Design real-time order tracking
4. Design product search
5. Design recommendation system
6. Design notification system for order updates
7. Design payment system with fraud detection
8. Design flash sale system
9. Design blob storage like S3
10. Design ride-sharing system like Uber
11. Design e-commerce product tracking service
12. Design book shop / online store system

**Related deep dive (payments → analytics correctness):**  
[`docs/data_eng/PAYMENT_TXN_ANALYTICS_PRINCIPAL_DESIGN.md`](docs/data_eng/PAYMENT_TXN_ANALYTICS_PRINCIPAL_DESIGN.md) — principal-level design for “every transaction must eventually appear correctly in analytics” (outbox, idempotent projection, reconciliation).

### System Design Answer Framework

Use this structure every time:

1. Clarify requirements
   - Functional requirements
   - Non-functional requirements
   - Scale
   - Latency
   - Availability
   - Reliability
   - Cost
   - Consistency needs

2. Define success metrics and constraints
   - QPS
   - p95 and p99 latency
   - Availability target
   - Error rate
   - Data freshness
   - Order success rate
   - Recovery time objective
   - Recovery point objective

3. Define APIs
   - REST or gRPC endpoints
   - Request and response shape
   - Idempotency keys where needed

4. Propose high-level architecture
   - API gateway
   - Services
   - Databases
   - Cache
   - Message queue
   - Search index
   - Workers
   - External integrations

5. Define data model
   - Core tables or entities
   - Indexes
   - Partitioning key
   - Retention needs

6. Deep dive where requested
   - Caching
   - Database choice
   - Messaging
   - Partitioning
   - Consistency
   - Backpressure
   - Retry strategy
   - Failure recovery

7. Cover reliability, scalability, and operations
   - Failure modes
   - Retries with exponential backoff
   - Dead-letter queues
   - Monitoring and alerting
   - Logging and tracing
   - Deployment and rollback
   - Reconciliation jobs

8. Explain tradeoffs
   - Strong consistency vs eventual consistency
   - Sync vs async
   - SQL vs NoSQL
   - Cache freshness vs latency
   - Cost vs reliability
   - Simplicity vs extensibility

### Coupang-Specific Design Themes

Tie your answers to e-commerce and logistics problems:

- Rocket delivery and order accuracy
- Inventory correctness across warehouses
- Preventing overselling
- Handling traffic spikes during sales
- Low-latency product discovery
- Reliable checkout and payment flows
- Real-time delivery status updates
- Observability for order and fulfillment failures

## Rounds 4 and 5: Focus Round and Bar Raiser

These rounds are project deep dives plus leadership principle questions. Prepare detailed stories from real projects.

### SBI Response Format

Use SBI: Situation, Behavior, Impact.

### Situation

Answer:

- What was the problem or context?
- Who was involved?
- What was at stake?
- Why did it matter?

### Behavior

Answer:

- What did you own?
- What actions did you take?
- What decisions did you make?
- How did you collaborate?
- How did you influence others?

### Impact

Answer:

- What improved or changed?
- What metrics moved?
- What was the business or customer impact?
- What did you learn?
- What would you do differently?

### Leadership Stories to Prepare

Prepare at least six strong stories.

#### Story 1: Large Technical Architecture

Examples:

- Migrated a monolith to services
- Built a high-scale platform
- Improved latency, cost, reliability, or developer velocity

Include:

- Initial architecture
- Constraints
- Options considered
- Tradeoffs
- Your decision-making process
- Rollout plan
- Metrics after launch

#### Story 2: Production Incident

Include:

- What failed
- Customer or business impact
- Your role during mitigation
- Root cause analysis
- Short-term fix
- Long-term prevention
- Metrics after the fix

#### Story 3: Hard Engineering Decision

Include:

- Competing options
- Tradeoffs
- Risks
- How you got alignment
- Final outcome

#### Story 4: Cross-Team Leadership

Include:

- Teams involved
- Conflict or ambiguity
- Your influence strategy
- Decision process
- Result

#### Story 5: Mentoring and Raising the Bar

Include:

- Team or engineer challenge
- How you coached
- Review or design process improvements
- Measurable impact

#### Story 6: Delivering Under Ambiguity

Include:

- Unclear requirements
- How you clarified the problem
- Milestones
- Stakeholder management
- Final impact

### Bar Raiser Tips

- Be specific.
- Use "I" for your ownership, not only "we".
- Include metrics wherever possible.
- Show judgment, not just execution.
- Explain tradeoffs.
- Show how your work changed team behavior or business outcomes.
- Ask the interviewer if they want more depth in a specific area.

## Seven-Day Preparation Plan

### Day 1: Coding - Core Data Structures

Practice:

- LRU Cache
- LFU Cache
- Min Stack
- All O(1) Data Structure

Goal:

- Be able to implement each cleanly and explain complexity.

### Day 2: Coding - Graphs and Grids

Practice:

- Number of Islands
- Distinct Islands
- Shortest Bridge
- Distance between closest islands
- Best Meeting Point
- Course Schedule

Goal:

- Be fluent with BFS, DFS, connected components, and visited-state handling.

### Day 3: Coding - Arrays, Stacks, and Strings

Practice:

- Sort Colors
- Largest Rectangle in Histogram
- Decode String
- Spiral Matrix
- Diagonal Traverse

Goal:

- Improve implementation speed and reduce edge-case mistakes.

### Day 4: Low-Level Design

Practice:

- File System
- Rate Limiter
- Cache
- Book Shop / Inventory Service

Goal:

- Practice class/API design, separation of responsibilities, and tests.

### Day 5: System Design - E-Commerce Core

Practice:

- Checkout / order system
- Inventory management
- Real-time order tracking
- Notification system

Goal:

- Cover consistency, idempotency, retries, messaging, and observability.

### Day 6: System Design - Scale and Search

Practice:

- Product search
- Recommendation system
- Blob storage
- Flash sale

Goal:

- Cover partitioning, caching, ranking, indexing, backpressure, and scale.

### Day 7: Behavioral and Mock Loop

Prepare:

- Six SBI stories
- Two project deep dives
- One full coding mock
- One full system design mock
- One leadership mock

Goal:

- Practice concise, structured answers under time pressure.

## High-Value Technical Concepts to Mention

Use these naturally when relevant:

- Idempotency
- Backpressure
- Circuit breakers
- Retry with exponential backoff
- Dead-letter queues
- Eventual consistency
- Strong consistency
- Saga pattern
- Outbox pattern
- Distributed tracing
- SLOs and SLIs
- Graceful degradation
- Horizontal scaling
- Data partitioning and sharding
- Read/write separation
- Cache invalidation
- Schema evolution
- Zero-downtime deployments
- Reconciliation jobs

## Questions to Ask Interviewers

Good Staff-level questions:

1. What are the biggest backend scalability challenges your team is solving now?
2. How does Coupang balance delivery speed with reliability?
3. What technical decisions would this Staff Engineer be expected to lead?
4. How are architecture decisions made across teams?
5. What does success look like for this role in the first six months?
6. What are the main operational pain points in the current systems?
7. How mature are observability, incident response, and postmortem practices?

## Official Prep Resources from the Guide

### Coding

- https://leetcode.com/studyplan/top-interview-150/
- The Algorithm Design Manual by Steven S. Skiena
- https://www.youtube.com/@NeetCode/playlists

### System Design

- https://github.com/ashishps1/awesome-system-design-resources

## Final Checklist

Before the interview, make sure you can:

- Solve core DSA problems with clean, runnable code.
- Explain brute force and optimized approaches.
- Dry run and test edge cases.
- State time and space complexity.
- Design e-commerce backend systems with clear tradeoffs.
- Discuss reliability, scalability, and operability.
- Present project deep dives using SBI.
- Support leadership stories with metrics.
- Communicate calmly and invite interviewer feedback.

