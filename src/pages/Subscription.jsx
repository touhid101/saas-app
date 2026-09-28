/**
 * The subscription screen — and, when access has run out, the whole application.
 *
 * <b>Two jobs, one page.</b> While a pharmacy is paid up this is a quiet detail
 * screen: what the plan is, when it ends, what is outstanding. The moment access
 * stops it becomes the only thing on screen, because there is nothing else the
 * user can usefully do and offering them a menu of things that all fail is worse
 * than saying so plainly.
 *
 * <b>Nothing here decides anything.</b> Whether access has run out, how many days
 * remain, whether the pharmacy is inside its grace period — all of it comes from
 * the server, resolved by the same code the API gate uses. A screen that counted
 * the days itself would eventually disagree with the gate, and on that day the
 * badge would cheerfully say "12 days left" over a locked-out application.
 */
import React, { useCallback, useEffect, useState } from "react";
import subscriptionService from "../services/subscriptionService.js";
import { Card, Pill } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";

const bdt = (n) =>
  "৳" + Number(n ?? 0).toLocaleString("en-IN", { minimumFractionDigits: 0, maximumFractionDigits: 0 });

const day = (iso) => {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
};

/** The one sentence that matters, in the words the state deserves. */
function headline(sub) {
  if (!sub) return { tone: "slate", text: "Loading…" };
  if (!sub.allowed) return { tone: "clay", text: sub.reason || "Access has stopped." };
  if (sub.inGrace)
    return {
      tone: "clay",
      text: `Your subscription ended on ${day(sub.expiresOn)}. You can keep working until `
          + `${day(sub.lastDayOfAccess)} — ${sub.daysRemaining} day${sub.daysRemaining === 1 ? "" : "s"} left.`,
    };
  if (sub.renewalDue)
    return {
      tone: "amber",
      text: `Your subscription ends on ${day(sub.expiresOn)}, in ${sub.daysRemaining} `
          + `day${sub.daysRemaining === 1 ? "" : "s"}.`,
    };
  return { tone: "sage", text: `Active until ${day(sub.expiresOn)}.` };
}

export default function Subscription({ blocking = false }) {
  const addToast = useToast();
  const [sub, setSub] = useState(null);
  const [plans, setPlans] = useState([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const load = useCallback(() => {
    setLoading(true);
    return Promise.all([subscriptionService.getMine(), subscriptionService.getPlans()])
      .then(([mine, p]) => { setSub(mine); setPlans(p); setError(null); })
      .catch((e) => setError(e?.message || "Could not load your subscription."))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  const renew = async (planId) => {
    setBusy(true);
    try {
      const inv = await subscriptionService.raiseRenewal(planId);
      addToast(`Invoice ${inv.invoiceNo} raised for ${bdt(inv.amount)}.`, "success");
      await load();
    } catch (e) {
      addToast(e?.message || "Could not raise the invoice.", "error");
    } finally {
      setBusy(false);
    }
  };

  const h = headline(sub);
  const outstanding = sub?.outstanding ?? [];

  return (
    <div className={blocking ? "sub-blocking" : "page"}>
      <div className="page-head">
        <div>
          <h1>{blocking ? "Subscription" : "Your subscription"}</h1>
          <div className="page-sub">{sub?.branchName ?? "…"}</div>
        </div>
        {!blocking && (
          <button className="btn btn-ghost btn-sm" onClick={load} disabled={loading}>
            {loading ? "Loading…" : "Refresh"}
          </button>
        )}
      </div>

      {error && <div className="empty-state" role="alert">{error}</div>}

      {sub && (
        <>
          <div className={`sub-headline sub-headline-${h.tone}`} role={sub.allowed ? undefined : "alert"}>
            <div className="sub-headline-text">{h.text}</div>
            {!sub.allowed && (
              <div className="sub-headline-sub">
                Your data is safe and nothing has been deleted. Renewing restores access
                immediately.
              </div>
            )}
          </div>

          <div className="grid-2" style={{ alignItems: "stretch", gap: 16 }}>
            <Card eyebrow="What you are on" title={sub.planName || "No plan"}
                  right={<Pill tone={sub.allowed ? "sage" : "clay"}>{sub.tenantStatus}</Pill>}>
              <dl className="sub-facts">
                <div><dt>Price</dt><dd className="mono">{bdt(sub.price)} / {sub.billingTerm === "YEARLY" ? "year" : "month"}</dd></div>
                <div><dt>Started</dt><dd>{day(sub.startsOn)}</dd></div>
                <div><dt>Ends</dt><dd>{day(sub.expiresOn)}</dd></div>
                <div>
                  <dt>Access until</dt>
                  <dd>
                    {day(sub.lastDayOfAccess)}
                    {sub.graceDays > 0 && (
                      <span className="sub-note"> — {sub.graceDays} days' grace after expiry</span>
                    )}
                  </dd>
                </div>
              </dl>
            </Card>

            <Card eyebrow={outstanding.length ? "Settle this to continue" : "Nothing outstanding"}
                  title={outstanding.length ? "Amount due" : "You are paid up"}>
              {outstanding.length === 0 ? (
                <div className="sub-paid">
                  No invoice is waiting. {sub.renewalDue
                    ? "Raise the next one below when you are ready."
                    : "Nothing to do."}
                </div>
              ) : (
                <div className="sub-invoices">
                  {outstanding.map((i) => (
                    <div key={i.invoiceId} className="sub-invoice">
                      <div>
                        <div className="sub-invoice-no mono">{i.invoiceNo}</div>
                        <div className="sub-invoice-period">
                          {day(i.periodFrom)} – {day(i.periodTo)}
                        </div>
                      </div>
                      <div className="sub-invoice-amt mono">{bdt(i.amount)}</div>
                    </div>
                  ))}
                  <p className="sub-howto">
                    Pay by bKash or bank transfer, then tell us the reference — an
                    administrator marks it received and your access returns straight away.
                  </p>
                </div>
              )}
            </Card>
          </div>

          {(sub.renewalDue || !sub.allowed) && (
            <Card eyebrow="Renew" title="Choose a plan"
                  right={<span className="ai-stat-sub">raising an invoice does not charge you</span>}>
              <div className="sub-plans">
                {plans.map((p) => (
                  <div key={p.planId}
                       className={`sub-plan ${p.planCode === sub.planCode ? "sub-plan-current" : ""}`}>
                    <div className="sub-plan-head">
                      <span className="sub-plan-name">{p.planName}</span>
                      {p.planCode === sub.planCode && <Pill tone="teal">current</Pill>}
                    </div>
                    <div className="sub-plan-price mono">
                      {bdt(p.price)}<span> / {p.billingTerm === "YEARLY" ? "year" : "month"}</span>
                    </div>
                    <div className="sub-plan-desc">{p.description}</div>
                    <div className="sub-plan-users">
                      {p.maxUsers ? `Up to ${p.maxUsers} users` : "Unlimited users"}
                    </div>
                    <button className="btn btn-primary btn-sm" disabled={busy || outstanding.length > 0}
                            onClick={() => renew(p.planId)}>
                      {outstanding.length > 0 ? "Invoice already raised" : "Raise invoice"}
                    </button>
                  </div>
                ))}
              </div>
            </Card>
          )}
        </>
      )}
    </div>
  );
}
