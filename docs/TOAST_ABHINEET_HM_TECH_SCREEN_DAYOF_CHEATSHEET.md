# Toast EM Screen — Day-of Cheat Sheet
**Abhineet Mishra · Sr Manager, SWE · Funds Management (Payments) · Bengaluru**  
**Role: Engineering Manager — Payments** · Tue 29 Sep 2026 · 10:00–10:45 IST  

**Full spoken answers:** `TOAST_ABHINEET_INTERVIEW_SCRIPT.md`

---

## Bar (remember this)

**Technical EM** — lead the team *and* dive deep on settlement. Not pure people-manager. Not Staff IC seeking hands-on-only.

---

## 90-sec open (EM)

> I'm Ramit — EM (and CIO) at Skydo. Built payments + settlement + recon from scratch, and built the team around it — ~12 engineers, 10K+ txn/day. My job was correctness under partner failure *and* raising the bar so that's a standard. Before that, VP Eng at Goldman (9). I want the EM seat on Funds Management — lead the people who own merchant payouts at Toast scale, stay deep enough to call money-path decisions.

---

## “What are you looking for?” (EM)

> EM owning a payments/funds team: (1) hire & grow a high-bar team, Seniors→Staff, (2) stay technical on settlement correctness, (3) partner with product/ops so Monday deposits being right is the outcome. Toast Funds Management is that job.

**Why EM not Staff?** Leverage through team + standards + roadmap; still dive deep. Org grew me into EM from founding eng — I know how to stay technical without bottlenecking.

---

## Who Abhineet is

| Fact | Implication |
|---|---|
| SEM, Funds Management / Payments BLR | Your hiring manager; EM reports into him |
| Posted EM role: high-performing teams + large-scale payments | People + payments depth both required |
| Ex-Amazon SDM, team 2→12, finance automation | Hire, ops excellence, metrics, ownership |

---

## Map stories → Funds Mgmt

| Toast | You as EM |
|---|---|
| Merchant payouts T+1/T+2 | Led settlement sweeps + bank timeout handling |
| Fees vs withholdings | Led recon / fee-math standards |
| Growing BLR Payments | Hired & structured platform vs product tracks |

---

## Deep dive — always attach leadership

Tech: idempotency (Redis + Postgres) · state machine · durable intent · UNKNOWN on timeout · recon  
Lead: playbook from near-miss · platform vs product bar · business SLOs → incidents −30% · ADRs  

---

## EM STAR pack (pick 3)

1. **Incident as EM** — halt retries, poll original key, UNKNOWN + runbook + SLO  
2. **Hire / bar** — failure-mode signal; platform vs product rubric  
3. **Grow Senior→Staff** — own an invariant (idempotency lib / recon engine)  
4. **Product conflict** — instant speed vs correctness; sequence, don't invert  
5. **Underperformance** — early, written, time-boxed; hard call on money path  

---

## Ask Abhineet (EM)

1. Great EM at 6 months — team, reliability, hiring, which product?  
2. What would my team own vs siblings? How do EMs partner with you?  
3. Hardest people/delivery challenge now?  
4. Speed vs money-path correctness under product pressure?  
5. What separates a strong EM from a no-hire here?

---

## Avoid

- “I want to go back to pure IC / hands-on only”  
- Pure people-manager with no settlement depth  
- Staff-only framing (“I operate at Staff”)  
- Exactly-once handwaves · POS dinner-rush theater  

## Metrics

12 eng @ Skydo · 9 @ GS · 10K+ txn/d · incidents −30% · ISO/SOC2 · mentored ~8
