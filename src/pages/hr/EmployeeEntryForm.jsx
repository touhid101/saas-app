/**
 * EmployeeEntryForm.jsx — one employee, entered or changed in one place.
 *
 * A staff record is held in two places that already existed: the identity
 * half on `/api/employees` (Master Data: code, name, role, department,
 * designation, phone, address) and the HR half on `/api/v1/hr/employees/{id}`
 * (personal detail, employment terms, pay basis, photograph). This form is
 * one front for both. It writes through the same endpoints the Master Data
 * screen and the HR profile use — nothing is stored twice and no second
 * employee API exists — in a fixed order: identity first (a new record needs
 * an id before anything can hang off it), then details, then pay, then the
 * photograph.
 *
 * Four steps and a review. Each step validates on its own; the review says
 * exactly what is about to be written. A save that fails part-way says which
 * part, and the form stays open on it with everything already saved kept.
 */
import React, { useEffect, useMemo, useRef, useState } from "react";
import { Modal, Field } from "../../components/UI.jsx";
import { useConfirm } from "../../lib/ConfirmProvider.jsx";
import EmployeeAvatar from "../../components/EmployeeAvatar.jsx";
import {
  HrStepper, HrFormSection, HrButton, HrEmployeePicker, Facts, Fact, PolicyNote, Ic, humanError, P,
} from "../../components/HrKit.jsx";
import {
  getEmployee, getEmployeeTypes, createEmployee, updateEmployee, getDepartments, getDesignations,
} from "../../services/masterDataService.js";
import {
  updateEmployeeDetails, setPayBasis, uploadEmployeeImage, removeEmployeeImage, getShifts, getStructures,
  getVocabulary, searchEmployees, IMAGE_ACCEPT, MARITAL_STATUSES, BLOOD_GROUPS, GENDERS, money, isoToday,
} from "../../services/hrService.js";
import uploadUrl from "../../lib/uploadUrl.js";

const STEPS = [
  { id: "identity",   label: "Identity & role", hint: "Code, name, department" },
  { id: "personal",   label: "Personal",        hint: "Dates, family, contact" },
  { id: "employment", label: "Employment",      hint: "Joined, manager, shift" },
  { id: "pay",        label: "Pay & bank",      hint: "Basic, structure, paid by" },
  { id: "review",     label: "Review",          hint: "What will be saved" },
];

const searchPeople = async (q) => (await searchEmployees(q)).items;
const titleCase = (s) => (s ? s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, " ") : "");
const PHONE_RE = /^[0-9+][0-9 \-]{6,19}$/;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const Problem = ({ children }) => (
  <div className="hr-lines-problem" role="status"><Ic path={P.alert} size={14} />{children}</div>
);

const blank = {
  // identity — /api/employees
  empId: "", empName: "", empType: "OTHER", departmentId: "", designationId: "",
  phone: "", email: "", gender: "", address: "", vehicleNo: "", status: "Y",
  // personal — HR details
  preferredName: "", dateOfBirth: "", maritalStatus: "", bloodGroup: "", nationalId: "",
  fatherName: "", motherName: "", emergencyContact: "", presentAddress: "",
  // employment — HR details
  hireDate: isoToday(), confirmationDate: "", employmentType: "", grade: "",
  managerId: "", managerName: "", shiftId: "", probationMonths: "", noticePeriodDays: "",
  pfApplicable: 0, gratuityApplicable: 0, taxApplicable: 0,
  // pay — HR pay basis
  basicSalary: "", salaryStructureId: "", salaryPaymentMode: "CASH",
  bankName: "", bankBranch: "", bankAccountNo: "", mobileWalletNo: "",
};

/**
 * @param employeeId  null to create; an id to edit
 * @param profile     the HR profile when editing (saves a read); optional
 * @param canPay      whether the pay step is offered (Edit on Employees)
 * @param onSaved(profile) after a successful save
 */
export default function EmployeeEntryForm({ employeeId = null, profile = null, canPay = true, onClose, onSaved, addToast }) {
  const confirm = useConfirm();
  const editing = employeeId != null;
  const steps = useMemo(() => (canPay ? STEPS : STEPS.filter((s) => s.id !== "pay")), [canPay]);

  const [at, setAt] = useState(0);
  const [f, setF] = useState(blank);
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(editing);
  const [saveErr, setSaveErr] = useState(null);      // { step, text }
  const [touched, setTouched] = useState({});
  const set = (patch) => { setDirty(true); setF((x) => ({ ...x, ...patch })); };
  const touch = (k) => setTouched((t) => ({ ...t, [k]: true }));

  /* Lookups, loaded once. */
  const [lk, setLk] = useState({ types: [], departments: [], designations: [], shifts: [], structures: [], vocab: {} });
  useEffect(() => {
    Promise.all([
      getEmployeeTypes().catch(() => []),
      getDepartments().catch(() => []),
      getDesignations().catch(() => []),
      getShifts(true).catch(() => []),
      getStructures(true).catch(() => []),
      getVocabulary().catch(() => ({})),
    ]).then(([types, departments, designations, shifts, structures, vocab]) => {
      setLk({ types: types || [], departments: departments || [], designations: designations || [], shifts: shifts || [], structures: structures || [], vocab: vocab || {} });
    });
  }, []);

  /* Editing: the identity half from Master Data, the rest from the profile. */
  useEffect(() => {
    if (!editing) return;
    let live = true;
    (async () => {
      try {
        const e = await getEmployee(employeeId);
        const p = profile || {};
        if (!live) return;
        setF({
          ...blank,
          empId: e.empId ?? "", empName: e.empName ?? "", empType: e.empType || "OTHER",
          departmentId: e.departmentId ?? "", designationId: e.designationId ?? "",
          phone: e.phone ?? "", email: e.email ?? p.email ?? "", gender: p.gender ?? "", address: p.address ?? "",
          vehicleNo: e.vehicleNo ?? "", status: e.status ?? "Y",
          preferredName: p.preferredName ?? "", dateOfBirth: p.dateOfBirth ?? "", maritalStatus: p.maritalStatus ?? "",
          bloodGroup: p.bloodGroup ?? "", nationalId: p.nationalId ?? "", fatherName: p.fatherName ?? "",
          motherName: p.motherName ?? "", emergencyContact: p.emergencyContact ?? "", presentAddress: p.presentAddress ?? "",
          hireDate: p.hireDate ?? "", confirmationDate: p.confirmationDate ?? "", employmentType: p.employmentType ?? "",
          grade: p.grade ?? "", managerId: p.managerId ?? "", managerName: p.managerName ?? "", shiftId: p.shiftId ?? "",
          probationMonths: p.probationMonths ?? "", noticePeriodDays: p.noticePeriodDays ?? "",
          pfApplicable: p.pfApplicable ?? 0, gratuityApplicable: p.gratuityApplicable ?? 0, taxApplicable: p.taxApplicable ?? 0,
          basicSalary: p.basicSalary ?? "", salaryStructureId: p.salaryStructureId ?? "",
          salaryPaymentMode: p.salaryPaymentMode ?? "CASH", bankName: p.bankName ?? "", bankBranch: p.bankBranch ?? "",
          bankAccountNo: p.bankAccountNo ?? "", mobileWalletNo: p.mobileWalletNo ?? "",
        });
        setPhoto({ url: p.imagePath || e.imagePath || null, initials: p.initials || e.initials || "" });
      } catch (err) {
        addToast(humanError(err, "The employee record could not be read."), "error");
      } finally { if (live) setLoading(false); }
    })();
    return () => { live = false; };
  }, [employeeId]); // eslint-disable-line react-hooks/exhaustive-deps

  /* ── The photograph ──
     Held as a file until the record exists; uploaded straight away when it
     does. Never shown as a broken image: the preview is a local object URL. */
  const [photo, setPhoto] = useState({ url: null, initials: "" });
  const [photoFile, setPhotoFile] = useState(null);
  const [photoBusy, setPhotoBusy] = useState(false);
  const fileInput = useRef(null);
  const [over, setOver] = useState(false);
  const preview = useMemo(() => (photoFile ? URL.createObjectURL(photoFile) : null), [photoFile]);
  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview); }, [preview]);

  const pickPhoto = async (file) => {
    if (!file) return;
    if (!/^image\/(jpeg|png|webp)$/.test(file.type)) { addToast("A photograph is a JPEG, PNG or WebP image.", "error"); return; }
    if (file.size > 5 * 1024 * 1024) { addToast("That image is over 5 MB. A smaller one, please.", "error"); return; }
    setDirty(true);
    if (!editing) { setPhotoFile(file); return; }
    setPhotoBusy(true);
    try {
      const { imagePath } = await uploadEmployeeImage(employeeId, file);
      setPhoto((p) => ({ ...p, url: imagePath })); setPhotoFile(null);
      addToast("Photograph updated.");
    } catch (err) { addToast(humanError(err, "That image was not accepted."), "error"); }
    finally { setPhotoBusy(false); }
  };
  const dropPhoto = async () => {
    if (!editing || !photo.url) { setPhotoFile(null); return; }
    const ok = await confirm({ title: "Remove this photograph?", body: "The record keeps their initials instead.", confirmLabel: "Remove", tone: "danger" });
    if (!ok) return;
    setPhotoBusy(true);
    try { await removeEmployeeImage(employeeId); setPhoto((p) => ({ ...p, url: null })); addToast("Photograph removed."); }
    catch (err) { addToast(humanError(err, "Could not remove it."), "error"); }
    finally { setPhotoBusy(false); }
  };

  /* ── Validation, per step ── */
  const problems = useMemo(() => {
    const p = { identity: [], personal: [], employment: [], pay: [], review: [] };
    if (!f.empId.trim()) p.identity.push("An employee code is needed — it is what every list and payslip shows.");
    else if (!/^[A-Za-z0-9._\-\/]{2,20}$/.test(f.empId.trim())) p.identity.push("The code is 2–20 letters, digits, dots, dashes or slashes.");
    if (!f.empName.trim()) p.identity.push("The full name is needed.");
    else if (f.empName.trim().length < 2) p.identity.push("The name is too short.");
    if (!f.empType) p.identity.push("Choose the role.");
    if (f.phone && !PHONE_RE.test(f.phone.trim())) p.identity.push("That phone number does not look right.");
    if (f.email && !EMAIL_RE.test(f.email.trim())) p.identity.push("That email address does not look right.");
    if (f.empType === "DELIVERY_MAN" && !f.vehicleNo.trim()) p.identity.push("A delivery man needs a vehicle number — the delivery list shows it.");
    if (f.dateOfBirth && f.dateOfBirth > isoToday()) p.personal.push("A date of birth cannot be in the future.");
    if (f.dateOfBirth && f.hireDate && f.dateOfBirth > f.hireDate) p.personal.push("The date of birth is after the joining date.");
    if (f.emergencyContact && !PHONE_RE.test(f.emergencyContact.trim())) p.personal.push("The emergency contact should be a phone number.");
    if (!f.hireDate) p.employment.push("The joining date is needed — service, leave and gratuity count from it.");
    if (f.confirmationDate && f.hireDate && f.confirmationDate < f.hireDate) p.employment.push("Confirmation is before the joining date.");
    if (f.probationMonths !== "" && !(Number(f.probationMonths) >= 0)) p.employment.push("Probation is a number of months.");
    if (f.noticePeriodDays !== "" && !(Number(f.noticePeriodDays) >= 0)) p.employment.push("Notice is a number of days.");
    if (f.basicSalary !== "" && !(Number(f.basicSalary) >= 0)) p.pay.push("Basic salary is an amount.");
    if (["BANK", "CHEQUE"].includes(f.salaryPaymentMode) && !f.bankAccountNo.trim() && !f.bankName.trim()) p.pay.push("Paid by bank needs the bank and the account.");
    if (f.salaryPaymentMode === "MOBILE" && !f.mobileWalletNo.trim()) p.pay.push("Paid by mobile wallet needs the wallet number.");
    return p;
  }, [f]);
  const stepId = steps[at].id;
  const stepProblems = problems[stepId] || [];
  const allProblems = steps.flatMap((s) => problems[s.id] || []);
  const firstBadStep = steps.findIndex((s) => (problems[s.id] || []).length > 0);

  const close = async () => {
    if (!dirty) return onClose();
    const ok = await confirm({ title: "Leave without saving?", body: "You have unsaved changes. If you leave now they are lost.", confirmLabel: "Leave", cancelLabel: "Stay", tone: "danger" });
    if (ok) onClose();
  };

  /* ── The save, in the order the record needs ── */
  const n = (v) => (v === "" || v == null ? null : v);
  const num = (v) => (v === "" || v == null ? null : Number(v));

  const save = async () => {
    if (busy || allProblems.length) { if (firstBadStep >= 0) setAt(firstBadStep); return; }
    setBusy(true); setSaveErr(null);
    let id = employeeId;
    let last = null;
    try {
      /* 1 · identity — the record itself. */
      const identity = {
        empId: f.empId.trim(), empName: f.empName.trim(), empType: f.empType,
        email: f.email.trim() || null, phone: f.phone.trim() || null,
        departmentId: num(f.departmentId), designationId: num(f.designationId),
        gender: f.gender ? titleCase(f.gender) : null, address: f.address.trim() || null,
        vehicleNo: f.vehicleNo.trim() || null, status: f.status || "Y",
      };
      let saved;
      try {
        saved = editing ? await updateEmployee(id, identity) : await createEmployee(identity);
      } catch (err) { throw Object.assign(err, { step: "identity" }); }
      id = saved?.id ?? id;

      /* 2 · personal & employment — the HR half. */
      try {
        last = await updateEmployeeDetails(id, {
          preferredName: n(f.preferredName.trim()), dateOfBirth: n(f.dateOfBirth), gender: n(f.gender),
          maritalStatus: n(f.maritalStatus), bloodGroup: n(f.bloodGroup), nationalId: n(f.nationalId.trim()),
          fatherName: n(f.fatherName.trim()), motherName: n(f.motherName.trim()), emergencyContact: n(f.emergencyContact.trim()),
          email: n(f.email.trim()), presentAddress: n(f.presentAddress.trim()), address: n(f.address.trim()),
          hireDate: n(f.hireDate), confirmationDate: n(f.confirmationDate), employmentType: n(f.employmentType),
          grade: n(f.grade.trim()), managerId: num(f.managerId), shiftId: num(f.shiftId),
          probationMonths: num(f.probationMonths), noticePeriodDays: num(f.noticePeriodDays),
          pfApplicable: Number(f.pfApplicable), gratuityApplicable: Number(f.gratuityApplicable), taxApplicable: Number(f.taxApplicable),
        });
      } catch (err) { throw Object.assign(err, { step: "personal", createdId: id }); }

      /* 3 · pay basis — only when offered and something is set. */
      if (canPay && (f.basicSalary !== "" || f.salaryStructureId || f.salaryPaymentMode)) {
        try {
          last = await setPayBasis(id, {
            basicSalary: num(f.basicSalary), salaryStructureId: num(f.salaryStructureId),
            employmentType: n(f.employmentType), confirmationDate: n(f.confirmationDate),
            salaryPaymentMode: n(f.salaryPaymentMode), bankName: n(f.bankName.trim()), bankBranch: n(f.bankBranch.trim()),
            bankAccountNo: n(f.bankAccountNo.trim()), mobileWalletNo: n(f.mobileWalletNo.trim()),
          });
        } catch (err) { throw Object.assign(err, { step: "pay", createdId: id }); }
      }

      /* 4 · the photograph, once there is a record to hang it on. */
      if (photoFile) {
        try {
          const { imagePath } = await uploadEmployeeImage(id, photoFile);
          if (last) last = { ...last, imagePath };
        } catch (err) {
          addToast(humanError(err, "The record was saved, but the photograph was not accepted. Add it from the profile."), "warning");
        }
      }

      addToast(editing ? `${f.empName.trim()} — record saved.` : `${f.empName.trim()} added as ${f.empId.trim()}.`);
      setDirty(false);
      onSaved?.(last, id);
    } catch (err) {
      const step = err.step || "identity";
      const created = !editing && err.createdId;
      setSaveErr({
        step,
        text: humanError(err, "The record could not be saved.")
          + (created ? ` The employee record itself (${f.empId.trim()}) was created — reopen it from the staff list to finish.` : ""),
      });
      setAt(Math.max(0, steps.findIndex((s) => s.id === step)));
      if (created) onSaved?.(null, err.createdId, true);
    } finally { setBusy(false); }
  };

  const next = () => { if (stepProblems.length) { Object.keys(f).forEach(touch); return; } setAt((i) => Math.min(steps.length - 1, i + 1)); };
  const back = () => setAt((i) => Math.max(0, i - 1));
  const isReview = stepId === "review";
  const dept = lk.departments.find((d) => String(d.id) === String(f.departmentId));
  const desig = lk.designations.find((d) => String(d.id) === String(f.designationId));
  const shift = lk.shifts.find((s) => String(s.id) === String(f.shiftId));
  const structure = lk.structures.find((s) => String(s.id) === String(f.salaryStructureId));
  const label = (list, v) => (list || []).find((o) => o.value === v)?.label || titleCase(v);

  return (
    <Modal title={editing ? `Edit — ${f.empName || "employee"}` : "Add an employee"}
           subtitle={editing ? "The identity half is shared with Master Data; the rest is HR's. Everything saves together."
                             : "One record, in four short steps. Nothing is written until the last one."}
           width={860} onClose={close}
           footer={<>
             <span className="hr-foot-status">
               Step <b>{at + 1}</b> of {steps.length}
               {allProblems.length > 0 && at === steps.length - 1 && <> · <b style={{ color: "var(--clay)" }}>{allProblems.length} to fix</b></>}
             </span>
             <HrButton variant="ghost" onClick={close} disabled={busy}>Cancel</HrButton>
             {at > 0 && <HrButton icon={P.back} onClick={back} disabled={busy}>Back</HrButton>}
             {!isReview ? (
               <HrButton variant="primary" onClick={next} disabled={loading}>Next</HrButton>
             ) : (
               <HrButton variant="primary" icon={P.check} busy={busy} busyLabel="Saving…" disabled={allProblems.length > 0 || loading} onClick={save}>
                 {editing ? "Save changes" : "Create employee"}
               </HrButton>
             )}
           </>}>

      <HrStepper steps={steps} at={at}
                 done={steps.filter((s, i) => i < at || (editing && !(problems[s.id] || []).length)).map((s) => s.id)}
                 errors={saveErr ? [saveErr.step] : steps.filter((s) => (problems[s.id] || []).length > 0 && s.id !== stepId && at === steps.length - 1).map((s) => s.id)}
                 onGo={(i) => setAt(i)} />

      {saveErr && <div style={{ marginTop: 12 }}><Problem>{saveErr.text}</Problem></div>}
      {loading && <p className="hr-dash" style={{ margin: "14px 0 0", fontSize: 13 }}>Reading the record…</p>}

      <div style={{ marginTop: 16 }}>
        {stepId === "identity" && (
          <>
            <HrFormSection icon={P.user} title="Photograph" hint="Optional. A JPEG, PNG or WebP under 5 MB; the record shows initials without one.">
              <div className="hr-form-full">
                <div className={`hr-photo${over ? " is-over" : ""}`}
                     onDragOver={(e) => { e.preventDefault(); setOver(true); }} onDragLeave={() => setOver(false)}
                     onDrop={(e) => { e.preventDefault(); setOver(false); pickPhoto(e.dataTransfer.files?.[0]); }}>
                  <div className="hr-photo-frame">
                    {preview || photo.url
                      ? <img src={preview || uploadUrl(photo.url)} alt="" />
                      : <EmployeeAvatar initials={photo.initials || (f.empName.trim().split(/\s+/).map((w) => w[0]).join("").slice(0, 2).toUpperCase() || "?")} name={f.empName} size={88} />}
                  </div>
                  <div className="hr-photo-text">
                    <b>{preview ? photoFile.name : photo.url ? "Photograph on record" : "No photograph yet"}</b>
                    <span>{preview ? `${(photoFile.size / 1024).toFixed(0)} KB · saved with the record` : "Drop an image here, or choose one."}</span>
                    <div className="hr-photo-actions">
                      <input ref={fileInput} type="file" accept={IMAGE_ACCEPT} style={{ display: "none" }}
                             onChange={(e) => { pickPhoto(e.target.files?.[0]); e.target.value = ""; }} />
                      <HrButton size="sm" icon={P.upload} busy={photoBusy} busyLabel="Uploading…" onClick={() => fileInput.current?.click()}>
                        {preview || photo.url ? "Replace" : "Choose a photo"}
                      </HrButton>
                      {(preview || photo.url) && <HrButton size="sm" variant="ghost" icon={P.trash} onClick={dropPhoto} disabled={photoBusy}>Remove</HrButton>}
                    </div>
                  </div>
                </div>
              </div>
            </HrFormSection>

            <HrFormSection icon={P.doc} title="Identity" hint="What every list, payslip and delivery shows. Shared with Master Data.">
              <Field label="Employee code *" hint="Unique at this branch. e.g. EMP-0125" error={touched.empId && problems.identity.find((m) => /code/i.test(m))}>
                <input value={f.empId} onBlur={() => touch("empId")} onChange={(e) => set({ empId: e.target.value })} placeholder="EMP-0125" maxLength={20} autoFocus={!editing} />
              </Field>
              <Field label="Full name *" error={touched.empName && problems.identity.find((m) => /name/i.test(m))}>
                <input value={f.empName} onBlur={() => touch("empName")} onChange={(e) => set({ empName: e.target.value })} placeholder="As on the national ID" maxLength={50} />
              </Field>
              <Field label="Role *" hint="What they do — it decides which screens can pick them.">
                <select value={f.empType} onChange={(e) => set({ empType: e.target.value })}>
                  {lk.types.length === 0 && <option value="OTHER">Other</option>}
                  {lk.types.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                </select>
              </Field>
              <Field label="Gender">
                <select value={f.gender} onChange={(e) => set({ gender: e.target.value })}>
                  <option value="">— not stated —</option>
                  {GENDERS.map((g) => <option key={g} value={g}>{titleCase(g)}</option>)}
                </select>
              </Field>
              <Field label="Department">
                <select value={f.departmentId} onChange={(e) => set({ departmentId: e.target.value })}>
                  <option value="">— none —</option>
                  {lk.departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
                </select>
              </Field>
              <Field label="Designation">
                <select value={f.designationId} onChange={(e) => set({ designationId: e.target.value })}>
                  <option value="">— none —</option>
                  {lk.designations.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
                </select>
              </Field>
              <Field label="Phone" error={touched.phone && problems.identity.find((m) => /phone/i.test(m))}>
                <input type="tel" value={f.phone} onBlur={() => touch("phone")} onChange={(e) => set({ phone: e.target.value })} placeholder="01XXXXXXXXX" maxLength={20} />
              </Field>
              <Field label="Email" error={touched.email && problems.identity.find((m) => /email/i.test(m))}>
                <input type="email" value={f.email} onBlur={() => touch("email")} onChange={(e) => set({ email: e.target.value })} placeholder="name@example.com" maxLength={50} />
              </Field>
              {(f.empType === "DELIVERY_MAN" || f.vehicleNo) && (
                <Field label={f.empType === "DELIVERY_MAN" ? "Vehicle number *" : "Vehicle number"} hint="Shown on the delivery list." error={touched.vehicleNo && problems.identity.find((m) => /vehicle/i.test(m))}>
                  <input value={f.vehicleNo} onBlur={() => touch("vehicleNo")} onChange={(e) => set({ vehicleNo: e.target.value })} maxLength={30} />
                </Field>
              )}
              {editing && (
                <Field label="Status" hint="Inactive staff leave every picker and every payroll run; the record is kept.">
                  <select value={f.status} onChange={(e) => set({ status: e.target.value })}>
                    <option value="Y">Active</option><option value="N">Inactive</option>
                  </select>
                </Field>
              )}
              <div className="hr-form-full">
                <Field label="Permanent address"><input value={f.address} onChange={(e) => set({ address: e.target.value })} maxLength={500} /></Field>
              </div>
            </HrFormSection>
            {stepProblems.length > 0 && Object.keys(touched).length > 0 && <Problem>{stepProblems[0]}</Problem>}
          </>
        )}

        {stepId === "personal" && (
          <>
            <HrFormSection icon={P.user} title="About them" hint="Everything here is optional; what is left blank simply stays blank.">
              <Field label="Preferred name" hint="What colleagues call them."><input value={f.preferredName} onChange={(e) => set({ preferredName: e.target.value })} maxLength={50} /></Field>
              <Field label="Date of birth" error={problems.personal.find((m) => /birth/i.test(m))}><input type="date" max={isoToday()} value={f.dateOfBirth} onChange={(e) => set({ dateOfBirth: e.target.value })} /></Field>
              <Field label="Marital status">
                <select value={f.maritalStatus} onChange={(e) => set({ maritalStatus: e.target.value })}>
                  <option value="">— not stated —</option>
                  {MARITAL_STATUSES.map((m) => <option key={m} value={m}>{titleCase(m)}</option>)}
                </select>
              </Field>
              <Field label="Blood group">
                <select value={f.bloodGroup} onChange={(e) => set({ bloodGroup: e.target.value })}>
                  <option value="">— not stated —</option>
                  {BLOOD_GROUPS.map((b) => <option key={b} value={b}>{b}</option>)}
                </select>
              </Field>
              <Field label="National ID"><input value={f.nationalId} onChange={(e) => set({ nationalId: e.target.value })} maxLength={30} /></Field>
              <Field label="Emergency contact" hint="A phone number to call." error={problems.personal.find((m) => /emergency/i.test(m))}>
                <input type="tel" value={f.emergencyContact} onChange={(e) => set({ emergencyContact: e.target.value })} maxLength={20} />
              </Field>
            </HrFormSection>
            <HrFormSection icon={P.people} title="Family & address">
              <Field label="Father's name"><input value={f.fatherName} onChange={(e) => set({ fatherName: e.target.value })} maxLength={100} /></Field>
              <Field label="Mother's name"><input value={f.motherName} onChange={(e) => set({ motherName: e.target.value })} maxLength={100} /></Field>
              <div className="hr-form-full">
                <Field label="Present address" hint="Where they live now, if different from the permanent address."><input value={f.presentAddress} onChange={(e) => set({ presentAddress: e.target.value })} maxLength={500} /></Field>
              </div>
            </HrFormSection>
            {stepProblems.length > 0 && <Problem>{stepProblems[0]}</Problem>}
          </>
        )}

        {stepId === "employment" && (
          <>
            <HrFormSection icon={P.calendar} title="Service" hint="Service, leave entitlement and gratuity all count from the joining date.">
              <Field label="Joined on *" error={problems.employment.find((m) => /joining date is needed/i.test(m))}><input type="date" value={f.hireDate} onChange={(e) => set({ hireDate: e.target.value })} /></Field>
              <Field label="Confirmed on" hint="Blank while on probation." error={problems.employment.find((m) => /Confirmation/i.test(m))}><input type="date" min={f.hireDate || undefined} value={f.confirmationDate} onChange={(e) => set({ confirmationDate: e.target.value })} /></Field>
              <Field label="Employment type">
                <select value={f.employmentType} onChange={(e) => set({ employmentType: e.target.value })}>
                  <option value="">— not set —</option>
                  {(lk.vocab.employmentType || []).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
              </Field>
              <Field label="Grade"><input value={f.grade} onChange={(e) => set({ grade: e.target.value })} maxLength={30} placeholder="Optional" /></Field>
              <Field label="Probation (months)"><input type="number" min="0" value={f.probationMonths} onChange={(e) => set({ probationMonths: e.target.value })} /></Field>
              <Field label="Notice period (days)"><input type="number" min="0" value={f.noticePeriodDays} onChange={(e) => set({ noticePeriodDays: e.target.value })} /></Field>
            </HrFormSection>
            <HrFormSection icon={P.people} title="Reporting & shift">
              <Field label="Reports to" hint="Approval chains that name the manager read this.">
                <HrEmployeePicker value={f.managerId} valueLabel={f.managerName} allowEmpty emptyLabel="Nobody"
                                  search={async (q) => (await searchPeople(q)).filter((x) => String(x.id) !== String(employeeId))}
                                  onChange={(id, row) => set({ managerId: id, managerName: row?.empName || "" })} />
              </Field>
              <Field label="Shift" hint="Late and overtime are judged against it.">
                <select value={f.shiftId} onChange={(e) => set({ shiftId: e.target.value })}>
                  <option value="">— the branch default —</option>
                  {lk.shifts.map((s) => <option key={s.id} value={s.id}>{s.shiftName}</option>)}
                </select>
              </Field>
            </HrFormSection>
            <HrFormSection icon={P.settings} title="Statutory" hint="Whether the schemes apply to this person. Each does nothing until the scheme itself is configured.">
              <Field label="Provident fund"><select value={f.pfApplicable} onChange={(e) => set({ pfApplicable: Number(e.target.value) })}><option value={0}>Not enrolled</option><option value={1}>Enrolled</option></select></Field>
              <Field label="Gratuity"><select value={f.gratuityApplicable} onChange={(e) => set({ gratuityApplicable: Number(e.target.value) })}><option value={0}>Not applicable</option><option value={1}>Applicable</option></select></Field>
              <Field label="Income tax"><select value={f.taxApplicable} onChange={(e) => set({ taxApplicable: Number(e.target.value) })}><option value={0}>Not applicable</option><option value={1}>Applicable</option></select></Field>
            </HrFormSection>
            {stepProblems.length > 0 && <Problem>{stepProblems[0]}</Problem>}
          </>
        )}

        {stepId === "pay" && (
          <>
            <HrFormSection icon={P.money} title="Pay basis" hint="Payroll skips anybody without a basic salary and a structure — this is where both are set.">
              <Field label="Basic salary" hint="Monthly, before allowances." error={problems.pay.find((m) => /Basic/i.test(m))}>
                <input type="number" min="0" step="0.01" value={f.basicSalary} onChange={(e) => set({ basicSalary: e.target.value })} placeholder="0.00" />
              </Field>
              <Field label="Salary structure" hint="The shape of the payslip.">
                <select value={f.salaryStructureId} onChange={(e) => set({ salaryStructureId: e.target.value })}>
                  <option value="">— none yet —</option>
                  {lk.structures.map((s) => <option key={s.id} value={s.id}>{s.structureName}</option>)}
                </select>
              </Field>
            </HrFormSection>
            <HrFormSection icon={P.wallet} title="Paid by" hint="How the salary reaches them.">
              <Field label="Payment mode">
                <select value={f.salaryPaymentMode} onChange={(e) => set({ salaryPaymentMode: e.target.value })}>
                  {(lk.vocab.paymentMode || [{ value: "CASH", label: "Cash" }, { value: "BANK", label: "Bank" }, { value: "CHEQUE", label: "Cheque" }, { value: "MOBILE", label: "Mobile" }]).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
              </Field>
              {["BANK", "CHEQUE"].includes(f.salaryPaymentMode) && (
                <>
                  <Field label="Bank"><input value={f.bankName} onChange={(e) => set({ bankName: e.target.value })} maxLength={100} /></Field>
                  <Field label="Branch"><input value={f.bankBranch} onChange={(e) => set({ bankBranch: e.target.value })} maxLength={100} /></Field>
                  <Field label="Account number"><input value={f.bankAccountNo} onChange={(e) => set({ bankAccountNo: e.target.value })} maxLength={50} /></Field>
                </>
              )}
              {f.salaryPaymentMode === "MOBILE" && (
                <Field label="Mobile wallet number"><input type="tel" value={f.mobileWalletNo} onChange={(e) => set({ mobileWalletNo: e.target.value })} maxLength={20} /></Field>
              )}
            </HrFormSection>
            {stepProblems.length > 0 && <Problem>{stepProblems[0]}</Problem>}
          </>
        )}

        {isReview && (
          <div className="hr-review">
            <div className="hr-review-id">
              <div className="hr-photo-frame is-small">
                {preview || photo.url ? <img src={preview || uploadUrl(photo.url)} alt="" /> : <EmployeeAvatar initials={(f.empName.trim().split(/\s+/).map((w) => w[0]).join("").slice(0, 2).toUpperCase() || "?")} name={f.empName} size={56} />}
              </div>
              <div>
                <b>{f.empName || "—"}</b>
                <span>{f.empId || "—"} · {label(lk.types, f.empType)}{desig ? ` · ${desig.name}` : ""}{dept ? ` · ${dept.name}` : ""}</span>
              </div>
            </div>
            {allProblems.length > 0 && (
              <Problem>{allProblems.length === 1 ? "One thing to fix: " : `${allProblems.length} things to fix, first: `}{allProblems[0]} <button type="button" className="hr-review-go" onClick={() => setAt(firstBadStep)}>Go to it</button></Problem>
            )}
            <ReviewBlock title="Identity & role" onEdit={() => setAt(0)}>
              <Fact label="Code" value={f.empId || "—"} /><Fact label="Name" value={f.empName || "—"} />
              <Fact label="Role" value={label(lk.types, f.empType)} /><Fact label="Gender" value={f.gender ? titleCase(f.gender) : "—"} />
              <Fact label="Department" value={dept?.name || "—"} /><Fact label="Designation" value={desig?.name || "—"} />
              <Fact label="Phone" value={f.phone || "—"} /><Fact label="Email" value={f.email || "—"} />
              {f.vehicleNo && <Fact label="Vehicle" value={f.vehicleNo} />}
              {editing && <Fact label="Status" value={f.status === "Y" ? "Active" : "Inactive"} />}
              <Fact label="Photograph" value={preview ? "New — uploaded on save" : photo.url ? "On record" : "None"} />
            </ReviewBlock>
            <ReviewBlock title="Personal" onEdit={() => setAt(1)}>
              <Fact label="Preferred name" value={f.preferredName || "—"} /><Fact label="Born" value={f.dateOfBirth || "—"} />
              <Fact label="Marital status" value={f.maritalStatus ? titleCase(f.maritalStatus) : "—"} /><Fact label="Blood group" value={f.bloodGroup || "—"} />
              <Fact label="National ID" value={f.nationalId || "—"} /><Fact label="Emergency contact" value={f.emergencyContact || "—"} />
              <Fact label="Father" value={f.fatherName || "—"} /><Fact label="Mother" value={f.motherName || "—"} />
            </ReviewBlock>
            <ReviewBlock title="Employment" onEdit={() => setAt(2)}>
              <Fact label="Joined" value={f.hireDate || "—"} /><Fact label="Confirmed" value={f.confirmationDate || "on probation"} />
              <Fact label="Type" value={label(lk.vocab.employmentType, f.employmentType) || "—"} /><Fact label="Grade" value={f.grade || "—"} />
              <Fact label="Reports to" value={f.managerName || (f.managerId ? `#${f.managerId}` : "nobody")} /><Fact label="Shift" value={shift?.shiftName || "branch default"} />
              <Fact label="Probation" value={f.probationMonths !== "" ? `${f.probationMonths} months` : "—"} /><Fact label="Notice" value={f.noticePeriodDays !== "" ? `${f.noticePeriodDays} days` : "—"} />
              <Fact label="PF" value={f.pfApplicable ? "Enrolled" : "No"} /><Fact label="Gratuity" value={f.gratuityApplicable ? "Applicable" : "No"} /><Fact label="Tax" value={f.taxApplicable ? "Applicable" : "No"} />
            </ReviewBlock>
            {canPay && (
              <ReviewBlock title="Pay & bank" onEdit={() => setAt(steps.findIndex((s) => s.id === "pay"))}>
                <Fact label="Basic salary" value={f.basicSalary !== "" ? money(f.basicSalary) : "not set — payroll will skip them"} num />
                <Fact label="Structure" value={structure?.structureName || "none — payroll will skip them"} />
                <Fact label="Paid by" value={label(lk.vocab.paymentMode, f.salaryPaymentMode) || f.salaryPaymentMode} />
                {["BANK", "CHEQUE"].includes(f.salaryPaymentMode) && <Fact label="Account" value={[f.bankName, f.bankBranch, f.bankAccountNo].filter(Boolean).join(" · ") || "—"} />}
                {f.salaryPaymentMode === "MOBILE" && <Fact label="Wallet" value={f.mobileWalletNo || "—"} />}
              </ReviewBlock>
            )}
            <PolicyNote>
              {editing
                ? "Saving writes the identity half to Master Data and the rest to the HR record, in that order. The delivery list, the invoice salesperson and every HR screen read from the same rows."
                : "Nothing has been written yet. Creating the employee writes the record first, then the HR detail, the pay basis and the photograph — if a later part is refused, the record is kept and the form says which part to finish."}
            </PolicyNote>
          </div>
        )}
      </div>
    </Modal>
  );
}

const ReviewBlock = ({ title, onEdit, children }) => (
  <section className="hr-review-block">
    <header><h4>{title}</h4><HrButton size="sm" variant="ghost" icon={P.edit} onClick={onEdit}>Edit</HrButton></header>
    <Facts>{children}</Facts>
  </section>
);
