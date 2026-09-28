/**
 * Where a pharmacy applies for an account.
 *
 * <b>It promises nothing it cannot keep.</b> Submitting creates one row in a
 * queue — no branch, no login, nothing live — so the confirmation says exactly
 * that rather than "welcome aboard". A sign-up screen that implies the account
 * exists produces a support call the moment they try to use it.
 *
 * <b>It does not confirm who already banks here.</b> A taken username comes back
 * as "already taken, choose another" whether it belongs to a live account or to
 * another application still waiting — the server deliberately does not say which,
 * and neither does this.
 *
 * <b>It asks which plan.</b> That was the first question every applicant was
 * asked on the phone, and it was not on the form, so it was asked again and
 * written down outside this system. The answer is a preference, not a purchase:
 * nothing is charged here, and a Super Admin still decides what a pharmacy goes
 * live on. The page says so, because a pricing table on a sign-up form otherwise
 * reads as a checkout.
 */
import React, { useEffect, useMemo, useState } from "react";
import apiClient from "../lib/apiClient.js";

const FIELDS = [
  { key: "pharmacyName", label: "Pharmacy name", required: true,  placeholder: "Friends Pharmacy, Mirpur" },
  { key: "contactName",  label: "Your name",     required: true,  placeholder: "Rahim Uddin" },
  { key: "contactPhone", label: "Mobile",        required: true,  placeholder: "017xxxxxxxx" },
  { key: "contactEmail", label: "Email",         required: false, placeholder: "you@example.com", type: "email" },
  { key: "address",      label: "Address",       required: false, placeholder: "Road 4, Mirpur 10, Dhaka" },
];

const bdt = (n) =>
  "৳" + Number(n ?? 0).toLocaleString("en-IN", { maximumFractionDigits: 0 });

/**
 * What a plan costs for a given term.
 *
 * <p>A plan's price covers one billing cycle, not one month — so a yearly plan
 * bought for six months is half its price, and a monthly plan bought for a year
 * is twelve times it. Multiplying every plan by the month count would overcharge
 * the yearly ones by a factor of twelve, which is the kind of error a customer
 * notices before you do.
 */
function quote(plan, term) {
  if (!plan || !term) return null;
  const cycles = plan.billingTerm === "YEARLY"
    ? term.termMonths / 12
    : term.termMonths;
  const gross = Number(plan.price ?? 0) * cycles;
  const discount = gross * (Number(term.discountPct ?? 0) / 100);
  return { gross, discount, net: gross - discount };
}

export default function Register({ onBack }) {
  const [form, setForm] = useState({});
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [done, setDone] = useState(null);

  // What is on sale. Fetched without a token, like the form itself.
  const [options, setOptions] = useState({ plans: [], terms: [] });
  const [picker, setPicker] = useState(null);   // the open popup's draft choice
  const [choice, setChoice] = useState(null);   // { planCode, termMonths }

  useEffect(() => {
    let alive = true;
    apiClient.get("/api/public/register/options")
      .then((d) => { if (alive) setOptions({ plans: d?.plans ?? [], terms: d?.terms ?? [] }); })
      // Silent. The plan is optional, and an applicant who cannot see the price
      // list should still be able to apply — failing the whole page because a
      // pricing panel would not load loses a customer over a decoration.
      .catch(() => {});
    return () => { alive = false; };
  }, []);

  const set = (k, v) => setForm((f) => ({ ...f, [k]: v }));

  const chosenPlan = useMemo(
    () => options.plans.find((p) => p.planCode === choice?.planCode) ?? null,
    [options.plans, choice]);
  const chosenTerm = useMemo(
    () => options.terms.find((t) => t.termMonths === choice?.termMonths) ?? null,
    [options.terms, choice]);

  const openPicker = () => setPicker(choice ?? {
    planCode: options.plans[0]?.planCode,
    termMonths: options.terms[0]?.termMonths,
  });

  const submit = async (e) => {
    e.preventDefault();
    setError(null);

    // Checked here only to save a round trip; the server checks it too, and the
    // server's answer is the one that counts.
    if (password.length < 6) return setError("Choose a password of at least six characters.");
    if (password !== confirm) return setError("The two passwords do not match.");

    setBusy(true);
    try {
      const res = await apiClient.post("/api/public/register", {
        ...form,
        adminUsername: username.trim(),
        adminPassword: password,
        preferredPlanCode: choice?.planCode ?? "",
        preferredTermMonths: choice?.termMonths == null ? "" : String(choice.termMonths),
      });
      setDone(res);
    } catch (err) {
      setError(err?.message || "Could not send your application. Please try again.");
    } finally {
      setBusy(false);
    }
  };

  if (done) {
    return (
      <div className="reg-shell">
        <div className="reg-card reg-done">
          <div className="reg-done-mark" aria-hidden="true">✓</div>
          <h1>Application received</h1>
          <p>
            Thank you. Your application for <strong>{form.pharmacyName}</strong> is with our
            team. Nothing is active yet — once it is approved we will call you on{" "}
            <strong>{form.contactPhone}</strong>, and you will be able to sign in as{" "}
            <strong>{username}</strong> with the password you just chose.
          </p>
          {chosenPlan && (
            <p className="reg-done-plan">
              We have noted that you are interested in <strong>{chosenPlan.planName}</strong>
              {chosenTerm ? <> for <strong>{chosenTerm.termName}</strong></> : null}. We will
              confirm the price with you before anything starts.
            </p>
          )}
          <button className="btn btn-primary" onClick={onBack}>Back to sign in</button>
        </div>
      </div>
    );
  }

  return (
    <div className="reg-shell">
      <form className="reg-card" onSubmit={submit}>
        <header className="reg-head">
          <div className="reg-mark" aria-hidden="true">✚</div>
          <div>
            <p className="reg-eyebrow">Friends Pharma ERP</p>
            <h1>Open a pharmacy account</h1>
          </div>
        </header>

        <p className="reg-lede">
          Tell us about your pharmacy. We review every application by hand, so nothing goes
          live until we have spoken to you — and nothing is charged today.
        </p>

        {error && <div className="reg-error" role="alert">{error}</div>}

        <div className="reg-section">
          <h2 className="reg-section-title"><span>1</span> Your pharmacy</h2>
          <div className="reg-fields">
            {FIELDS.map((f) => (
              <label key={f.key} className={f.key === "address" ? "reg-wide" : ""}>
                <span>{f.label}{!f.required && <em> — optional</em>}</span>
                <input
                  type={f.type || "text"}
                  value={form[f.key] ?? ""}
                  required={f.required}
                  placeholder={f.placeholder}
                  onChange={(e) => set(f.key, e.target.value)}
                />
              </label>
            ))}
          </div>
        </div>

        <div className="reg-section">
          <h2 className="reg-section-title"><span>2</span> The account you will sign in with</h2>
          <div className="reg-fields">
            <label>
              <span>Username</span>
              <input value={username} required autoComplete="username"
                     onChange={(e) => setUsername(e.target.value)} />
            </label>
            <label>
              <span>Password</span>
              <input type="password" value={password} required autoComplete="new-password"
                     onChange={(e) => setPassword(e.target.value)} />
            </label>
            <label>
              <span>Confirm password</span>
              <input type="password" value={confirm} required autoComplete="new-password"
                     onChange={(e) => setConfirm(e.target.value)} />
            </label>
          </div>
        </div>

        {/* The plan is last and optional: someone who knows what they want can say
            so, and someone who does not can still apply and be advised. */}
        {options.plans.length > 0 && (
          <div className="reg-section">
            <h2 className="reg-section-title">
              <span>3</span> Which plan interests you<em> — optional</em>
            </h2>

            {chosenPlan ? (
              <div className="reg-pick">
                <div className="reg-pick-main">
                  <strong>{chosenPlan.planName}</strong>
                  <span>{chosenTerm?.termName ?? "term not chosen"}</span>
                </div>
                <div className="reg-pick-price">
                  {(() => {
                    const q = quote(chosenPlan, chosenTerm);
                    return q ? (
                      <>
                        <b>{bdt(q.net)}</b>
                        {q.discount > 0 && <s>{bdt(q.gross)}</s>}
                      </>
                    ) : <b>{bdt(chosenPlan.price)}</b>;
                  })()}
                </div>
                <button type="button" className="btn btn-ghost btn-sm" onClick={openPicker}>
                  Change
                </button>
              </div>
            ) : (
              <button type="button" className="reg-pick-empty" onClick={openPicker}>
                <strong>Choose a plan and term</strong>
                <span>See what each one includes and what it would cost</span>
              </button>
            )}
          </div>
        )}

        <div className="reg-actions">
          <button type="button" className="btn btn-ghost" onClick={onBack}>Back to sign in</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? "Sending…" : "Send application"}
          </button>
        </div>

        <p className="reg-foot">
          Nothing is charged now. Choosing a plan tells us what to quote — we confirm the
          price with you before your account starts.
        </p>
      </form>

      {picker && (
        <PlanPicker
          plans={options.plans}
          terms={options.terms}
          draft={picker}
          onDraft={setPicker}
          onClear={() => { setChoice(null); setPicker(null); }}
          onCancel={() => setPicker(null)}
          onConfirm={() => { setChoice(picker); setPicker(null); }}
        />
      )}
    </div>
  );
}

/**
 * The plan and term chooser.
 *
 * <p>Both decisions live in one dialog because neither answers on its own: a
 * plan without a term has no price, and a term without a plan has nothing to
 * apply to. Splitting them into two popups would make the applicant open the
 * first one twice.
 */
function PlanPicker({ plans, terms, draft, onDraft, onClear, onCancel, onConfirm }) {
  useEffect(() => {
    const onKey = (e) => { if (e.key === "Escape") onCancel(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onCancel]);

  const plan = plans.find((p) => p.planCode === draft.planCode);
  const term = terms.find((t) => t.termMonths === draft.termMonths);
  const q = quote(plan, term);

  return (
    <div className="modal-backdrop" onClick={onCancel}>
      <div className="modal modal-wide plan-modal" onClick={(e) => e.stopPropagation()}
           role="dialog" aria-label="Choose a plan and term">
        <div className="plan-modal-head">
          <h3>Plans</h3>
          <p>
            Pick what suits you. This is a preference, not a purchase — we confirm everything
            with you before your account starts.
          </p>
        </div>

        <div className="plan-grid">
          {plans.map((p) => {
            const on = p.planCode === draft.planCode;
            return (
              <button
                key={p.planCode}
                type="button"
                className={`plan-card${on ? " is-on" : ""}`}
                aria-pressed={on}
                onClick={() => onDraft({ ...draft, planCode: p.planCode })}
              >
                <span className="plan-card-name">{p.planName}</span>
                <span className="plan-card-price">
                  {bdt(p.price)}
                  <em>/{p.billingTerm === "YEARLY" ? "year" : "month"}</em>
                </span>
                {p.description && <span className="plan-card-desc">{p.description}</span>}
                <span className="plan-card-users">
                  {p.maxUsers ? `Up to ${p.maxUsers} user${p.maxUsers === 1 ? "" : "s"}` : "Unlimited users"}
                </span>
              </button>
            );
          })}
        </div>

        <div className="plan-terms">
          <h4>For how long?</h4>
          <div className="plan-term-row">
            {terms.map((t) => {
              const on = t.termMonths === draft.termMonths;
              return (
                <button
                  key={t.termMonths}
                  type="button"
                  className={`plan-term${on ? " is-on" : ""}`}
                  aria-pressed={on}
                  onClick={() => onDraft({ ...draft, termMonths: t.termMonths })}
                >
                  {t.termName}
                  {Number(t.discountPct) > 0 && (
                    <em>save {Number(t.discountPct)}%</em>
                  )}
                </button>
              );
            })}
          </div>
        </div>

        {q && (
          <div className="plan-quote">
            <div className="plan-quote-line">
              <span>{plan.planName} · {term.termName}</span>
              <span className="mono">{bdt(q.gross)}</span>
            </div>
            {q.discount > 0 && (
              <div className="plan-quote-line plan-quote-off">
                <span>Term discount {Number(term.discountPct)}%</span>
                <span className="mono">− {bdt(q.discount)}</span>
              </div>
            )}
            <div className="plan-quote-line plan-quote-total">
              <span>Indicative total</span>
              <span className="mono">{bdt(q.net)}</span>
            </div>
          </div>
        )}

        <div className="reg-actions plan-modal-actions">
          <button type="button" className="btn btn-ghost" onClick={onClear}>
            No plan for now
          </button>
          <div className="plan-modal-right">
            <button type="button" className="btn btn-ghost" onClick={onCancel}>Cancel</button>
            <button type="button" className="btn btn-primary" onClick={onConfirm}
                    disabled={!plan || !term}>
              Use this
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
