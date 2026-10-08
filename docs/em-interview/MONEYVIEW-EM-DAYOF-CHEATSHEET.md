# MoneyView EM — Day-of Cheat Sheet
**Thu 8 Oct 2026 · 16:00–17:00 IST · R1 Engineering Manager**  
**Meet:** meet.google.com/vkt-hfda-czd · (US) +1 956-332-5792 PIN 660 376 154#  
**Panel:** Subrata Parial (Director of Engineering) · Rishika Singh (TA) · you  
**Full spoken answers:** `MONEYVIEW-EM-SPOKEN-ANSWERS.md` · deeper context: `MONEYVIEW-EM-INTERVIEW-PREP.md`

## 90-sec open
EM 10+ yrs fintech. **Skydo:** lead 12 on payments/settlement **10K+/day**, idempotency/recon/locks; CIO → **ISO 27001 + SOC 2**. **Goldman VP:** 9 eng, multi-TB risk compute (promoted &lt;1 yr). **MoneyView alum (2019–20):** payment demand gen + recon — millions of debit instructions/day, manual ops **−60%**. Back because MV is now multi-product credit-led platform (PL + UPI + Gold + cards) at unicorn/IPO scale — want to lead teams that own correctness at consumer volume.

## Why MoneyView (3 beats)
1. **Alumni + growth** — I built debit/recon muscle here; company jumped from PL-heavy to super-app + WhizDM balance sheet + UPI. Same problems, larger surface.
2. **Correctness at Middle India scale** — money + trust + RBI/partner constraints. Same invariants I run at Skydo.
3. **Hands-on EM seat** — Subrata’s org ships product (UPI, Digital Gold) *and* AI. I lead by delivery system + bar, not backlog theater.

## Why leave Skydo / why now
Team + platform + compliance bar done. Next = consumer lending / payments surface where EM craft compounds at India retail volume. No boss/comp/burnout story. Respect for MV chapter — returning with EM + GS + compliance depth I didn’t have in 2019.

## Subrata lens (Director Eng · Amazon Mgr · MFine DoE · “AI innovations”)
Peer-up / hiring manager. Cares about: **shipped products** (UPI, Digital Gold), **AI with guardrails**, **team velocity under regulation**, **ownership**. Speak **lending funnel + payments rails + partner integrations + incident discipline** — not only cross-border Skydo lore. Bridge: “Same intent→side-effect→recon ladder, different product surface.”

## 3 lead stories (metric + lesson)
1. **MoneyView alum:** millions debit/day · ops **−60%** — idempotent retries + SLA scheduling · *operability is the product*
2. **Skydo reliability:** incidents **−30%** · recon **0.6% → &lt;0.02%** TPV — observability + shared state vocab
3. **Bar/hiring:** design coverage ~30→100% · mentored 8 · team of 12 · rubric before debrief

## Backup stories
Kill/phase compliance vs speed · ISO/SOC2 as platform · AI coding standards · Goldman latency clarity · Hard feedback with receipts · Failure you owned (partner webhook)

## Mgmt 30s
1:1 weekly IC-owned · ~20% debt budget · underperform = write→coach→PIP · senior conflict = ADRs→decide · skip-level crunch = cut scope not weekends · hiring = work-sample + written feedback before debrief

## Tech walk (pick one)
**Default — Skydo payments:** intent before side effect · idempotency · lock+fencing · recon ladder · no magic exactly-once  
**MV-native — debit/collections:** multi-gateway · SLA schedule · idempotent retry · exception queue · ops cut −60%  
**Lending-shaped:** apply→bureau/KYC→decision→disburse→EMI/debit→collections · partner LSP vs WhizDM books · fraud real-time

## Ask him (pick 3)
1. Hardest reliability/correctness problem on your teams right now (UPI vs lending vs platform)?  
2. Great EM first 90 days here = hiring / delivery system / product bet?  
3. How do you split roadmap when Product velocity vs risk/compliance vs eng debt collide?  
4. Where is AI real vs hype in underwriting / ops / SDLC on your teams?  
5. Team shape I’ll inherit + how hands-on should the EM be weekly?

## Avoid
Trash 2019 MV or Skydo · fake RBI expertise · pure people-manager · “exactly-once delivery” · rambling &gt;2.5m · no numbers · treating R1 as casual catch-up

## Metrics card
MV: millions debit/d · ops −60%  
Skydo: 10K+ txn/d · incidents −30% · recon 0.6%→&lt;0.02% · onboarding hours→min · ISO/SOC2 · team 12 / mentored 8  
GS: team 9 · VP &lt;1y
