/**
 * How the platform is doing — revenue, renewals, and who has stopped using it.
 *
 * <b>What it opens with.</b> Not revenue. Revenue is the number you already know;
 * the ones worth a morning are the pharmacies that have gone quiet and the
 * renewals that are about to lapse, because both are still fixable and neither
 * appears on a billing report. A customer paying their subscription and ringing
 * up nothing has already stopped using the product and has not got round to
 * cancelling — that is a phone call this week, not a surprise next quarter.
 *
 * <b>What it deliberately does not show.</b> Not one invoice, customer or line of
 * anybody's trade. Running a platform requires knowing that a pharmacy has gone
 * quiet; it does not require knowing what they sold.
 *
 * <b>Nothing here decides anything.</b> Which renewals are urgent and which
 * pharmacies are at risk are judged on the server, so a second screen showing the
 * same list cannot disagree with this one.
 */
import React, { useCallback, useEffect, useState } from "react";
import apiClient from "../lib/apiClient.js";
import { Card, Pill } from "../components/UI.jsx";
import { HBars } from "../components/DashCharts.jsx";
import { useToast } from "../hooks/useToast.js";

const bdt = (n) => "৳" + Math.round(Number(n ?? 0)).toLocaleString("en-IN");
const day = (iso) => {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso
    : d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
};
const monthLabel = (m) => {
  if (!m) return "";
  const [y, mo] = m.split("-");
  return new Date(Number(y), Number(mo) - 1, 1)
    .toLocaleDateString("en-GB", { month: "short", year: "2-digit" });
};

function Stat({ label, value, sub, tone }) {
  return (
    <div className={`pc-stat ${tone ? `pc-stat-${tone}` : ""}`}>
      <div className="pc-stat-v mono">{value}</div>
      <div className="pc-stat-l">{label}</div>
      {sub && <div className="pc-stat-s">{sub}</div>}
    </div>
  );
}

export default function PlatformConsole({ onNavigate }) {
  const addToast = useToast();
  const [d, setD] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(() => {
    setLoading(true);
    return apiClient.get("/api/admin/console")
      .then(setD)
      .catch((e) => addToast(e?.message || "Could not load the console.", "error"))
      .finally(() => setLoading(false));
  }, [addToast]);

  useEffect(() => { load(); }, [load]);

  const status = d?.tenantsByStatus ?? {};
  const totalTenants = Object.values(status).reduce((a, b) => a + b, 0);
  const atRisk = (d?.activity ?? []).filter((a) => a.quiet || a.falling);
  const urgent = (d?.expiringSoon ?? []).filter((e) => e.urgent);

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Platform</h1>
          <div className="page-sub">
            {totalTenants} pharmac{totalTenants === 1 ? "y" : "ies"}
            {d && ` · as at ${day(d.asOf)}`}
          </div>
        </div>
        <div style={{ display: "flex", gap: 8 }}>
          {/* Reached from here rather than the sidebar, like Pharmacies: these
              are platform-operator screens, and a menu entry every pharmacy
              administrator can see but not open is worse than no entry. */}
          <button className="btn btn-ghost btn-sm" onClick={() => onNavigate?.("passwords")}>
            Passwords
          </button>
          <button className="btn btn-ghost btn-sm" onClick={load} disabled={loading}>
            {loading ? "Loading…" : "Refresh"}
          </button>
        </div>
      </div>

      {d && (
        <>
          {/* Attention before accounting: these are the ones still fixable. */}
          {(atRisk.length > 0 || urgent.length > 0 || d.pendingApplications > 0) ? (
            <div className="dash-alerts">
              {d.pendingApplications > 0 && (
                <button className="dash-alert dash-alert-info" onClick={() => onNavigate?.("tenants")}>
                  <div className="dash-alert-top">
                    <Pill tone="teal">WAITING</Pill>
                    <span className="dash-alert-count mono">{d.pendingApplications}</span>
                  </div>
                  <div className="dash-alert-title">Applications to review</div>
                  <div className="dash-alert-detail">Nobody can trade until you approve them.</div>
                  <div className="dash-alert-go">Open the queue →</div>
                </button>
              )}
              {urgent.length > 0 && (
                <div className="dash-alert dash-alert-warning">
                  <div className="dash-alert-top">
                    <Pill tone="amber">RENEWALS</Pill>
                    <span className="dash-alert-count mono">{urgent.length}</span>
                  </div>
                  <div className="dash-alert-title">Ending within a week</div>
                  <div className="dash-alert-detail">
                    {urgent.slice(0, 2).map((e) => e.branchName).join(", ")}
                    {urgent.length > 2 && ` and ${urgent.length - 2} more`}
                  </div>
                </div>
              )}
              {atRisk.length > 0 && (
                <div className="dash-alert dash-alert-critical">
                  <div className="dash-alert-top">
                    <Pill tone="clay">AT RISK</Pill>
                    <span className="dash-alert-count mono">{atRisk.length}</span>
                  </div>
                  <div className="dash-alert-title">Paying but barely trading</div>
                  <div className="dash-alert-detail">
                    A pharmacy that has stopped selling has stopped using this. Worth a call.
                  </div>
                </div>
              )}
            </div>
          ) : (
            <div className="dash-allclear">
              Nothing needs attention. Every pharmacy is trading, no renewal is close, and
              no application is waiting.
            </div>
          )}

          <div className="pc-stats">
            <Stat label="Monthly recurring revenue" value={bdt(d.monthlyRecurringRevenue)}
                  sub="yearly plans counted as a twelfth" />
            <Stat label="Outstanding" value={bdt(d.outstandingTotal)}
                  sub={`${d.outstandingCount} unpaid invoice${d.outstandingCount === 1 ? "" : "s"}`}
                  tone={d.outstandingCount ? "clay" : undefined} />
            <Stat label="Active" value={status.ACTIVE ?? 0} sub={`of ${totalTenants} pharmacies`} />
            <Stat label="Suspended or expired" value={(status.SUSPENDED ?? 0) + (status.EXPIRED ?? 0)}
                  tone={(status.SUSPENDED ?? 0) + (status.EXPIRED ?? 0) ? "clay" : undefined} />
            <Stat label="At risk" value={d.atRiskCount} sub="quiet or falling by half"
                  tone={d.atRiskCount ? "amber" : undefined} />
          </div>

          <div className="grid-2" style={{ alignItems: "stretch", gap: 16 }}>
            <Card eyebrow="Month by month" title="Collected"
                  right={<span className="ai-stat-sub">subscription payments</span>}>
              {/* HBars, not the grouped bars the dashboard uses: those are built for a
                  revenue-and-cost pair and this is one series. Bending a component
                  designed for two values into showing one is how a chart ends up
                  silently plotting zero for half of itself. */}
              <HBars
                rows={(d.collections ?? []).slice(-12).map((c) => ({
                  label: monthLabel(c.month), value: Number(c.collected),
                }))}
                format={bdt}
                empty="No subscription payment has been recorded yet." />
            </Card>

            <Card eyebrow="Who is actually using it" title="Sales in the last 30 days"
                  right={<span className="ai-stat-sub">invoices raised</span>}>
              <HBars
                rows={(d.activity ?? [])
                  .slice()
                  .sort((a, b) => b.sales30 - a.sales30)
                  .slice(0, 8)
                  .map((a) => ({ label: a.branchName, value: a.sales30 }))}
                format={(v) => v.toLocaleString()}
                empty="No pharmacy has traded in the last month." />
            </Card>
          </div>

          <Card eyebrow="Still fixable" title="Renewals due within a month">
            {(d.expiringSoon ?? []).length === 0 ? (
              <div className="sub-paid">Nothing is close to expiry.</div>
            ) : (
              <div className="table-scroll">
                <table className="data-table">
                  <thead>
                    <tr><th>Pharmacy</th><th>Plan</th><th>Ends</th>
                        <th className="num">Days left</th><th className="num">Grace</th></tr>
                  </thead>
                  <tbody>
                    {d.expiringSoon.map((e) => (
                      <tr key={e.branchId}>
                        <td>{e.branchName}</td>
                        <td>{e.planName}</td>
                        <td>{day(e.expiresOn)}</td>
                        <td className="num mono">
                          <span className={e.urgent ? "tenant-standing-clay" : "tenant-standing-amber"}>
                            {e.daysLeft}
                          </span>
                        </td>
                        <td className="num mono">+{e.graceDays}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <div className="grid-2" style={{ alignItems: "stretch", gap: 16 }}>
            <Card eyebrow="Oldest first" title="Unpaid invoices">
              {(d.outstanding ?? []).length === 0 ? (
                <div className="sub-paid">Everything has been settled.</div>
              ) : (
                <div className="table-scroll">
                  <table className="data-table">
                    <thead>
                      <tr><th>Invoice</th><th>Pharmacy</th>
                          <th className="num">Amount</th><th className="num">Overdue</th></tr>
                    </thead>
                    <tbody>
                      {d.outstanding.map((i) => (
                        <tr key={i.invoiceId}>
                          <td className="mono">{i.invoiceNo}</td>
                          <td>{i.branchName}</td>
                          <td className="num mono">{bdt(i.amount)}</td>
                          <td className="num mono">
                            {i.daysOverdue > 0
                              ? <span className="tenant-standing-clay">{i.daysOverdue}d</span>
                              : "—"}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>

            <Card eyebrow="Every pharmacy" title="Trading, month on month">
              <div className="table-scroll">
                <table className="data-table">
                  <thead>
                    <tr><th>Pharmacy</th><th className="num">30d</th><th className="num">Prev 30d</th>
                        <th>Last sale</th><th className="num">Users</th></tr>
                  </thead>
                  <tbody>
                    {(d.activity ?? []).map((a) => (
                      <tr key={a.branchId} className={a.quiet || a.falling ? "row-picked" : ""}>
                        <td>
                          {a.branchName}
                          {a.quiet && <Pill tone="clay">quiet</Pill>}
                          {!a.quiet && a.falling && <Pill tone="amber">falling</Pill>}
                        </td>
                        <td className="num mono">{a.sales30.toLocaleString()}</td>
                        <td className="num mono">{a.salesPrev30.toLocaleString()}</td>
                        <td>{day(a.lastSale)}</td>
                        <td className="num mono">{a.users}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          </div>

          <Card eyebrow="What has happened" title="Subscription trail">
            {(d.audit ?? []).length === 0 ? (
              <div className="sub-paid">Nothing has happened yet.</div>
            ) : (
              <div className="table-scroll">
                <table className="data-table">
                  <thead>
                    <tr><th>When</th><th>Pharmacy</th><th>Event</th><th>Detail</th><th>By</th></tr>
                  </thead>
                  <tbody>
                    {d.audit.slice(0, 40).map((a, i) => (
                      <tr key={i}>
                        <td className="mono">{a.actedAt ? a.actedAt.replace("T", " ").slice(0, 16) : "—"}</td>
                        <td>{a.branchName ?? "—"}</td>
                        <td><Pill tone={a.eventType === "PAYMENT" ? "sage"
                                       : a.eventType === "EXPIRED" ? "clay" : "teal"}>
                          {a.eventType}
                        </Pill></td>
                        <td>{a.detail ?? "—"}</td>
                        <td>{a.actedBy ?? "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </>
      )}
    </div>
  );
}
